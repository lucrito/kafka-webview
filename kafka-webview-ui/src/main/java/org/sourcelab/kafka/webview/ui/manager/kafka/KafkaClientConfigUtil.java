/**
 * MIT License
 *
 * Copyright (c) 2017, 2018, 2019 SourceLab.org (https://github.com/SourceLabOrg/kafka-webview/)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.sourcelab.kafka.webview.ui.manager.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.sourcelab.kafka.webview.ui.manager.kafka.config.ClusterConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class to DRY out common Kafka client configuration options that apply to multiple client types.
 */
public class KafkaClientConfigUtil {
    /**
     * Path on filesystem where keystores are persisted.
     */
    private final String keyStoreRootPath;

    /**
     * Static prefix to pre-pend to all consumerIds.
     */
    private final String consumerIdPrefix;

    /**
     * Request timeout in milliseconds.
     */
    private final int requestTimeoutMs = 15000;

    /**
     * Constructor.
     * @param keyStoreRootPath Path to where keystore files are persisted on the file system.
     * @param consumerIdPrefix Application configuration value for a standard prefix to apply to all consumerIds.
     */
    public KafkaClientConfigUtil(final String keyStoreRootPath, final String consumerIdPrefix) {
        this.keyStoreRootPath = keyStoreRootPath;
        this.consumerIdPrefix = consumerIdPrefix;
    }

    /**
     * Builds a map of all common Kafka client configuration settings.
     * @param clusterConfig ClusterConfig instance to use as basis for configuration/
     * @param consumerId Id of consumer to use.  This will be prefixed with consumerIdPrefix property.
     * @return a new Map containing the configuration options.
     */
    public Map<String, Object> applyCommonSettings(final ClusterConfig clusterConfig, final String consumerId) {
        return applyCommonSettings(clusterConfig, consumerId, new HashMap<>());
    }

    /**
     * Builds a map of all common Kafka client configuration settings.
     * @param clusterConfig ClusterConfig instance to use as basis for configuration/
     * @param consumerId Id of consumer to use.  This will be prefixed with consumerIdPrefix property.
     * @param config Apply configuration to existing map.
     * @return a new Map containing the configuration options.
     */
    private Map<String, Object> applyCommonSettings(
        final ClusterConfig clusterConfig,
        final String consumerId,
        final Map<String, Object> config
    ) {
        // Generate consumerId with our configured static prefix.
        final String prefixedConsumerId = consumerIdPrefix.concat("-").concat(consumerId);

        // Set common config items
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, clusterConfig.getConnectString());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);

        // ClientId is intended to be unique for each user session.
        // See Issue-57 https://github.com/SourceLabOrg/kafka-webview/issues/57#issuecomment-363508531
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, prefixedConsumerId);

        // Consumer groups can be defined at the cluster level, view level, or one is generated dynamically.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, getConsumerGroupId(clusterConfig, consumerId));

        // Partitioning Strategy is to always get all partitions no matter how many subscribers in a consumer group.
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, DuplicatedAssignor.class.getName());

        // Optionally configure SSL
        applySslSettings(clusterConfig, config);

        // Optionally configure SASL
        applySaslSettings(clusterConfig, config);

        return config;
    }

    /**
     * If SSL is configured for this cluster, apply the settings.
     * @param clusterConfig Cluster configuration definition to source values from.
     * @param config Config map to apply settings to.
     */
    private void applySslSettings(final ClusterConfig clusterConfig, final Map<String, Object> config) {
        // Optionally configure SSL
        if (!clusterConfig.isUseSsl()) {
            return;
        }
        if (clusterConfig.isUseSasl()) {
            config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_SSL.name);
        } else {
            config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name);

            // KeyStore and KeyStore password only needed if NOT using SASL
            config.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keyStoreRootPath + "/" + clusterConfig.getKeyStoreFile());
            config.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, clusterConfig.getKeyStorePassword());
        }
        config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, keyStoreRootPath + "/" + clusterConfig.getTrustStoreFile());
        config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, clusterConfig.getTrustStorePassword());
    }

    /**
     * If SASL is configured for this cluster, apply the settings.
     * @param clusterConfig Cluster configuration definition to source values from.
     * @param config Config map to apply settings to.
     */
    private void applySaslSettings(final ClusterConfig clusterConfig, final Map<String, Object> config) {
        // If we're using SSL, we've already configured everything for SASL too...
        if (!clusterConfig.isUseSasl()) {
            return;
        }

        // If not using SSL
        if (clusterConfig.isUseSsl()) {
            // SASL+SSL
            config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_SSL.name);

            // Keystore and keystore password not required if using SASL+SSL
            config.remove(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG);
            config.remove(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG);
        } else {
            // Just SASL PLAINTEXT
            config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_PLAINTEXT.name);
        }
        config.put(SaslConfigs.SASL_MECHANISM, clusterConfig.getSaslMechanism());
        config.put(SaslConfigs.SASL_JAAS_CONFIG, clusterConfig.getSaslJaas());
    }

    /**
     * Generate the default consumer group id to be used.  This value may be overriden by a view specific value
     * later.
     *
     * @param clusterConfig cluster configuration definition.
     * @param consumerId Id of consumer to use.
     * @return non-null, non-empty value to use as consumer group.
     */
    private String getConsumerGroupId(final ClusterConfig clusterConfig, final String consumerId) {
        if (clusterConfig.hasDefaultConsumerGroupId()) {
            return clusterConfig.getDefaultConsumerGroupId();
        }
        // Backwards compatibility for now.
        return consumerIdPrefix.concat("-").concat(consumerId);
    }
}
