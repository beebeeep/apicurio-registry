package io.apicurio.registry.resolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.apicurio.registry.resolver.data.Record;
import io.apicurio.registry.resolver.strategy.ArtifactCoordinates;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import io.apicurio.registry.rest.client.models.ArtifactContent;
import io.apicurio.registry.rest.client.models.IfExists;
import io.apicurio.registry.rest.client.models.VersionMetaData;
import io.apicurio.registry.utils.IoUtil;

/**
 * Default implementation of {@link SchemaResolver}
 *
 */
public class DefaultSchemaResolver<S, T> extends AbstractSchemaResolver<S, T> {

    private boolean autoCreateArtifact;
    private String autoCreateBehavior;
    private boolean findLatest;
    private boolean dereference;

    /**
     * @see io.apicurio.registry.resolver.AbstractSchemaResolver#reset()
     */
    @Override
    public void reset() {
        super.reset();
    }

    /**
     * @see io.apicurio.registry.resolver.AbstractSchemaResolver#configure(java.util.Map, io.apicurio.registry.resolver.SchemaParser)
     */
    @Override
    public void configure(Map<String, ?> configs, SchemaParser<S, T> schemaParser) {
        super.configure(configs, schemaParser);

        if (artifactResolverStrategy.loadSchema() && !schemaParser.supportsExtractSchemaFromData()) {
            throw new IllegalStateException("Wrong configuration");
        }

        this.autoCreateArtifact = config.autoRegisterArtifact();
        this.dereference = config.serializerDereference();
        this.autoCreateBehavior = config.autoRegisterArtifactIfExists();
        this.findLatest = config.findLatest();
    }

    /**
     * @see io.apicurio.registry.resolver.SchemaResolver#resolveSchema(io.apicurio.registry.resolver.data.Record)
     */
    @Override
    public SchemaLookupResult<S> resolveSchema(Record<T> data) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(data.payload());


        ParsedSchema<S> parsedSchema;
        if (artifactResolverStrategy.loadSchema() && schemaParser.supportsExtractSchemaFromData()) {
            parsedSchema = schemaParser.getSchemaFromData(data, dereference);
        } else {
            parsedSchema = null;
        }

        final ArtifactReference artifactReference = resolveArtifactReference(data, parsedSchema, false, null);

        return getSchemaFromCache(artifactReference)
                .orElseGet(() -> getSchemaFromRegistry(parsedSchema, data, artifactReference));
    }

    private Optional<SchemaLookupResult<S>> getSchemaFromCache(ArtifactReference artifactReference) {
        if (artifactReference.getGlobalId() != null && schemaCache.containsByGlobalId(artifactReference.getGlobalId())) {
            return Optional.of(resolveSchemaByGlobalId(artifactReference.getGlobalId()));
        } else if (artifactReference.getContentId() != null && schemaCache.containsByContentId(artifactReference.getContentId())) {
            return Optional.of(resolveSchemaByContentId(artifactReference.getContentId()));
        } else if (artifactReference.getContentHash() != null && schemaCache.containsByContentHash(artifactReference.getContentHash())) {
            return Optional.of(resolveSchemaByContentHash(artifactReference.getContentHash()));
        } else if (schemaCache.containsByArtifactCoordinates(ArtifactCoordinates.fromArtifactReference(artifactReference))) {
            return Optional.of(resolveSchemaByArtifactCoordinatesCached(ArtifactCoordinates.fromArtifactReference(artifactReference)));
        }
        return Optional.empty();
    }


    private SchemaLookupResult<S> getSchemaFromRegistry(ParsedSchema<S> parsedSchema, Record<T> data, ArtifactReference artifactReference) {

        if (autoCreateArtifact) {

            if (schemaParser.supportsExtractSchemaFromData()) {

                if (parsedSchema == null) {
                    parsedSchema = schemaParser.getSchemaFromData(data, dereference);
                }

                if (parsedSchema.hasReferences()) {
                    //List of references lookup, to be used to create the references for the artifact
                    final List<SchemaLookupResult<S>> schemaLookupResults = handleArtifactReferences(data, parsedSchema);
                    return handleAutoCreateArtifact(parsedSchema, artifactReference, schemaLookupResults);
                } else {
                    return handleAutoCreateArtifact(parsedSchema, artifactReference);
                }
            } else if (config.getExplicitSchemaLocation() != null && schemaParser.supportsGetSchemaFromLocation()) {
                parsedSchema = schemaParser.getSchemaFromLocation(config.getExplicitSchemaLocation());
                return handleAutoCreateArtifact(parsedSchema, artifactReference);
            }
        }

        if (findLatest || artifactReference.getVersion() != null) {
            return resolveSchemaByCoordinates(artifactReference.getGroupId(), artifactReference.getArtifactId(), artifactReference.getVersion());
        }

        if (schemaParser.supportsExtractSchemaFromData()) {
            if (parsedSchema == null) {
                parsedSchema = schemaParser.getSchemaFromData(data);
            }
            return handleResolveSchemaByContent(parsedSchema, artifactReference);
        }

        return resolveSchemaByCoordinates(artifactReference.getGroupId(), artifactReference.getArtifactId(), artifactReference.getVersion());
    }

    private List<SchemaLookupResult<S>> handleArtifactReferences(Record<T> data, ParsedSchema<S> parsedSchema) {
        final List<SchemaLookupResult<S>> referencesLookup = new ArrayList<>();

        for (ParsedSchema<S> referencedSchema : parsedSchema.getSchemaReferences()) {

            List<SchemaLookupResult<S>> nestedReferences = handleArtifactReferences(data, referencedSchema);

            if (nestedReferences.isEmpty()) {
                referencesLookup.add(handleAutoCreateArtifact(referencedSchema, resolveArtifactReference(data, referencedSchema, true, referencedSchema.referenceName())));
            } else {
                referencesLookup.add(handleAutoCreateArtifact(referencedSchema, resolveArtifactReference(data, referencedSchema, true, referencedSchema.referenceName()), nestedReferences));
            }
        }
        return referencesLookup;
    }

    /**
     * @see io.apicurio.registry.resolver.SchemaResolver#resolveSchemaByArtifactReference (io.apicurio.registry.resolver.strategy.ArtifactReferenceImpl)
     */
    @Override
    public SchemaLookupResult<S> resolveSchemaByArtifactReference(ArtifactReference reference) {
        if (reference == null) {
            throw new IllegalStateException("artifact reference cannot be null");
        }

        if (reference.getContentId() != null) {
            return resolveSchemaByContentId(reference.getContentId());
        }
        if (reference.getContentHash() != null) {
            return resolveSchemaByContentHash(reference.getContentHash());
        }
        if (reference.getGlobalId() != null) {
            return resolveSchemaByGlobalId(reference.getGlobalId());
        }

        return resolveSchemaByCoordinates(reference.getGroupId(), reference.getArtifactId(), reference.getVersion());
    }

    private SchemaLookupResult<S> resolveSchemaByCoordinates(String groupId, String artifactId, String version) {
        if (artifactId == null) {
            throw new IllegalStateException("artifactId cannot be null");
        }

        ArtifactReference reference = ArtifactReference.builder().groupId(groupId).artifactId(artifactId).version(version).build();

        return resolveSchemaByArtifactReferenceCached(reference);
    }

    protected SchemaLookupResult<S> resolveSchemaByContentId(long contentId) {
        return schemaCache.getByContentId(contentId, contentIdKey -> {

            // it's impossible to retrieve more info about the artifact with only the contentId, and that's ok for this case
            InputStream rawSchema = null;
            ParsedSchemaImpl<S> ps = null;
            try {
                rawSchema = client.ids().contentIds().byContentId(contentIdKey).get();

                //Get the artifact references
                final List<io.apicurio.registry.rest.client.models.ArtifactReference> artifactReferences =
                        client.ids().contentIds().byContentId(contentId).references().get();
                //If there are any references for the schema being parsed, resolve them before parsing the schema
                final Map<String, ParsedSchema<S>> resolvedReferences = resolveReferences(artifactReferences);

                byte[] schema = rawSchema.readAllBytes();
                S parsed = schemaParser.parseSchema(schema, resolvedReferences);



                ps = new ParsedSchemaImpl<S>()
                        .setParsedSchema(parsed)
                        .setRawSchema(schema);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            SchemaLookupResult.SchemaLookupResultBuilder<S> result = SchemaLookupResult.builder();
            return result
                    .contentId(contentIdKey)
                    .parsedSchema(ps)
                    .build();
        });
    }

    protected SchemaLookupResult<S> resolveSchemaByContentHash(String contentHash) {
        return schemaCache.getByContentHash(contentHash, contentHashKey -> {
            // it's impossible to retrieve more info about the artifact with only the contentHash, and that's ok for this case
            InputStream rawSchema = null;
            ParsedSchemaImpl<S> ps = null;

            rawSchema = client.ids().contentHashes().byContentHash(contentHashKey).get();

            //Get the artifact references
            final List<io.apicurio.registry.rest.client.models.ArtifactReference> artifactReferences = client
                    .ids().contentHashes().byContentHash(contentHashKey).references().get();
            //If there are any references for the schema being parsed, resolve them before parsing the schema
            final Map<String, ParsedSchema<S>> resolvedReferences = resolveReferences(artifactReferences);

            byte[] schema = IoUtil.toBytes(rawSchema);
            S parsed = schemaParser.parseSchema(schema, resolvedReferences);

            ps = new ParsedSchemaImpl<S>()
                    .setParsedSchema(parsed)
                    .setRawSchema(schema);
            SchemaLookupResult.SchemaLookupResultBuilder<S> result = SchemaLookupResult.builder();

            return result
                    .contentHash(contentHashKey)
                    .parsedSchema(ps)
                    .build();
        });
    }

    /**
     * Search by content may not work for some usecases of our Serdes implementations.
     * For example when serializing protobuf messages, the schema inferred from the data
     * may not be equal to the .proto file schema uploaded in the registry.
     */
    private SchemaLookupResult<S> handleResolveSchemaByContent(ParsedSchema<S> parsedSchema,
                                                               final ArtifactReference artifactReference) {

        String rawSchemaString = IoUtil.toString(parsedSchema.getRawSchema());

        return schemaCache.getByContent(rawSchemaString, contentKey -> {
            ArtifactContent content = new ArtifactContent();
            content.setContent(contentKey);
            VersionMetaData artifactMetadata = client
                    .groups()
                    .byGroupId(artifactReference.getGroupId() == null ? "default" : artifactReference.getGroupId())
                    .artifacts()
                    .byArtifactId(artifactReference.getArtifactId())
                    .post(content, config -> {
                        config.queryParameters.canonical = true;
                        config.headers.add("Content-Type", "application/get.extended+json");
                    });

            SchemaLookupResult.SchemaLookupResultBuilder<S> result = SchemaLookupResult.builder();

            loadFromArtifactMetaData(artifactMetadata, result);

            result.parsedSchema(parsedSchema);

            return result.build();
        });
    }

    private SchemaLookupResult<S> handleAutoCreateArtifact(ParsedSchema<S> parsedSchema,
                                                           final ArtifactReference artifactReference) {
        String rawSchemaString = IoUtil.toString(parsedSchema.getRawSchema());

        return schemaCache.getByContent(rawSchemaString, contentKey -> {

            ArtifactContent content = new ArtifactContent();
            content.setContent(rawSchemaString);
            VersionMetaData artifactMetadata = client
                        .groups()
                        .byGroupId(artifactReference.getGroupId() == null ? "default" : artifactReference.getGroupId())
                        .artifacts()
                        .post(content, config -> {
                            config.queryParameters.ifExists = IfExists.forValue(this.autoCreateBehavior);
                            config.queryParameters.canonical = false;
                            if (artifactReference.getArtifactId() != null)
                                config.headers.add("X-Registry-ArtifactId", artifactReference.getArtifactId());
                            if (schemaParser.artifactType() != null)
                                config.headers.add("X-Registry-ArtifactType", schemaParser.artifactType());
                            if (artifactReference.getVersion() != null)
                                config.headers.add("X-Registry-Version", artifactReference.getVersion());
                        });

            SchemaLookupResult.SchemaLookupResultBuilder<S> result = SchemaLookupResult.builder();

            loadFromArtifactMetaData(artifactMetadata, result);

            result.parsedSchema(parsedSchema);

            return result.build();
        });
    }

    private SchemaLookupResult<S> handleAutoCreateArtifact(ParsedSchema<S> parsedSchema,
                                                           final ArtifactReference artifactReference, List<SchemaLookupResult<S>> referenceLookups) {

        String rawSchemaString = IoUtil.toString(parsedSchema.getRawSchema());

        final List<io.apicurio.registry.rest.client.models.ArtifactReference> artifactReferences = parseReferences(referenceLookups);

        return schemaCache.getByContent(rawSchemaString, contentKey -> {

            ArtifactContent content = new ArtifactContent();
            content.setContent(rawSchemaString);
            content.setReferences(artifactReferences);
            VersionMetaData artifactMetadata = client
                        .groups()
                        .byGroupId(artifactReference.getGroupId() == null ? "default" : artifactReference.getGroupId())
                        .artifacts()
                        .post(content, config -> {
                            config.queryParameters.ifExists = IfExists.forValue(this.autoCreateBehavior);
                            config.queryParameters.canonical = false;
                            if (artifactReference.getArtifactId() != null)
                                config.headers.add("X-Registry-ArtifactId", artifactReference.getArtifactId());
                            if (schemaParser.artifactType() != null)
                                config.headers.add("X-Registry-ArtifactType", schemaParser.artifactType());
                            if (artifactReference.getVersion() != null)
                                config.headers.add("X-Registry-Version", artifactReference.getVersion());
                            config.headers.add("Content-Type", "application/create.extended+json");
                        });

            SchemaLookupResult.SchemaLookupResultBuilder<S> result = SchemaLookupResult.builder();

            loadFromArtifactMetaData(artifactMetadata, result);

            result.parsedSchema(parsedSchema);

            return result.build();
        });
    }

    private List<io.apicurio.registry.rest.client.models.ArtifactReference> parseReferences(List<SchemaLookupResult<S>> referenceLookups) {
        final List<io.apicurio.registry.rest.client.models.ArtifactReference> artifactReferences = new ArrayList<>();

        referenceLookups.forEach(referenceLookup -> {
            io.apicurio.registry.rest.client.models.ArtifactReference artifactReferenceLookup = new io.apicurio.registry.rest.client.models.ArtifactReference();
            artifactReferenceLookup.setArtifactId(referenceLookup.getArtifactId());
            artifactReferenceLookup.setGroupId(referenceLookup.getGroupId());
            artifactReferenceLookup.setName(referenceLookup.getParsedSchema().referenceName());
            artifactReferenceLookup.setVersion(referenceLookup.getVersion());
            artifactReferences.add(artifactReferenceLookup);
        });

        return artifactReferences;
    }

    private SchemaLookupResult<S> resolveSchemaByArtifactCoordinatesCached(ArtifactCoordinates artifactCoordinates) {
        return schemaCache.getByArtifactCoordinates(artifactCoordinates, artifactCoordinatesKey -> resolveByCoordinates(artifactCoordinatesKey.getGroupId(), artifactCoordinatesKey.getArtifactId(), artifactCoordinatesKey.getVersion()));
    }


    private SchemaLookupResult<S> resolveSchemaByArtifactReferenceCached(ArtifactReference artifactReference) {
        if (artifactReference.getGlobalId() != null) {
            return schemaCache.getByGlobalId(artifactReference.getGlobalId(), this::resolveSchemaByGlobalId);
        } else if (artifactReference.getContentId() != null) {
            return schemaCache.getByContentId(artifactReference.getContentId(), this::resolveSchemaByContentId);
        } else if (artifactReference.getContentHash() != null) {
            return schemaCache.getByContentHash(artifactReference.getContentHash(), this::resolveSchemaByContentHash);
        } else {
            return schemaCache.getByArtifactCoordinates(ArtifactCoordinates.fromArtifactReference(artifactReference), artifactReferenceKey -> resolveByCoordinates(artifactReferenceKey.getGroupId(), artifactReferenceKey.getArtifactId(), artifactReferenceKey.getVersion()));
        }
    }

    private SchemaLookupResult<S> resolveByCoordinates(String groupId, String artifactId, String version) {
        SchemaLookupResult.SchemaLookupResultBuilder<S> result = SchemaLookupResult.builder();
        //TODO if getArtifactVersion returns the artifact version and globalid in the headers we can reduce this to only one http call
        
        if (version == null) {
            version = "branch=latest";
        }

        S parsed = null;
        byte[] schema = null;
        Long gid;
        VersionMetaData metadata = client.groups().byGroupId(groupId).artifacts().byArtifactId(artifactId).versions().byVersionExpression(version).get();
        loadFromArtifactMetaData(metadata, result);
        gid = metadata.getGlobalId();

        InputStream rawSchema = client.ids().globalIds().byGlobalId(gid).get();

        //Get the artifact references
        final List<io.apicurio.registry.rest.client.models.ArtifactReference> artifactReferences = client
                .ids().globalIds().byGlobalId(gid).references().get();
        //If there are any references for the schema being parsed, resolve them before parsing the schema
        final Map<String, ParsedSchema<S>> resolvedReferences = resolveReferences(artifactReferences);

        schema = IoUtil.toBytes(rawSchema);
        parsed = schemaParser.parseSchema(schema, resolvedReferences);

        result.parsedSchema(new ParsedSchemaImpl<S>()
                .setParsedSchema(parsed)
                .setRawSchema(schema));

        return result.build();
    }
}
