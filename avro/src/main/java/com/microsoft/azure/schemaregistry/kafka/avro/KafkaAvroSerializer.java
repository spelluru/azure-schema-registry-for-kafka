// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.azure.schemaregistry.kafka.avro;

import com.azure.core.credential.TokenCredential;
import com.azure.data.schemaregistry.AbstractDataSerializer;
import com.azure.data.schemaregistry.avro.AvroByteEncoder;
import com.azure.data.schemaregistry.client.CachedSchemaRegistryClientBuilder;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

/**
 * Serializer implementation for Kafka producer, implementing the Kafka Serializer interface.
 *
 * Objects are converted to byte arrays containing an Avro-encoded payload and is prefixed with a GUID pointing
 * to the matching Avro schema in Azure Schema Registry.
 *
 * Currently, sending Avro GenericRecords and SpecificRecords is supported.  Avro reflection has been disabled.
 *
 * @see AbstractDataSerializer See abstract parent class for implementation details
 * @see KafkaAvroDeserializer See deserializer class for downstream deserializer implementation
 */
public class KafkaAvroSerializer extends AbstractDataSerializer
        implements Serializer<Object> {

    /**
     * Empty constructor for Kafka producer
     */
    public KafkaAvroSerializer() {
        super();
    }

    /**
     * Configures serializer instance.
     *
     * @param props Map of properties used to configure instance.
     * @param isKey Indicates if serializing record key or value.  Required by Kafka serializer interface,
     *              no specific functionality implemented for key use.
     *
     * @see KafkaAvroSerializerConfig Serializer will use configs found in KafkaAvroSerializerConfig.
     */
    @Override
    public void configure(Map<String, ?> props, boolean isKey) {
        KafkaAvroSerializerConfig config = new KafkaAvroSerializerConfig(props);
        String registryUrl = config.getSchemaRegistryUrl();
        TokenCredential credential = config.getCredential();
        Integer maxSchemaMapSize = config.getMaxSchemaMapSize();

        CachedSchemaRegistryClientBuilder builder = new CachedSchemaRegistryClientBuilder()
                .endpoint(registryUrl)
                .credential(credential);

        if (maxSchemaMapSize != null) {
            builder.maxSchemaMapSize(maxSchemaMapSize);
        }

        this.schemaRegistryClient = builder.buildClient();

        this.schemaGroup = config.getSchemaGroup();
        this.autoRegisterSchemas = config.getAutoRegisterSchemas();

        this.setByteEncoder(new AvroByteEncoder());
    }


    /**
     * Serializes GenericRecord or SpecificRecord into a byte array, containing a GUID reference to schema
     * and the encoded payload.
     *
     * Null behavior matches Kafka treatment of null values.
     *
     * @param topic Topic destination for record. Required by Kafka serializer interface, currently not used.
     * @param record Object to be serialized, may be null
     * @return byte[] payload for sending to EH Kafka service, may be null
     * @throws SerializationException Exception catchable by core Kafka producer code
     */
    @Override
    public byte[] serialize(String topic, Object record) throws SerializationException {
        // null needs to treated specially since the client most likely just wants to send
        // an individual null value instead of making the subject a null type. Also, null in
        // Kafka has a special meaning for deletion in a topic with the compact retention policy.
        // Therefore, we will bypass schema registration and return a null value in Kafka, instead
        // of an Avro encoded null.
        if (record == null) {
            return null;
        }

        try {
            return serializeImpl(record);
        } catch (com.azure.data.schemaregistry.SerializationException e) {
            // convert into kafka exception
            throw new SerializationException(e.getCause());
        }
    }

    @Override
    public void close() { }
}
