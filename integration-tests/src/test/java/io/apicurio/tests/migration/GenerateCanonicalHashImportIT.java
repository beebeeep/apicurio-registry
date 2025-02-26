package io.apicurio.tests.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.apicurio.registry.client.auth.VertXAuthFactory;
import io.apicurio.registry.model.BranchId;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.VersionState;
import io.apicurio.registry.utils.IoUtil;
import io.apicurio.registry.utils.impexp.ArtifactBranchEntity;
import io.apicurio.registry.utils.impexp.ArtifactVersionEntity;
import io.apicurio.registry.utils.impexp.ContentEntity;
import io.apicurio.registry.utils.impexp.EntityWriter;
import io.apicurio.tests.ApicurioRegistryBaseIT;
import io.apicurio.tests.serdes.apicurio.JsonSchemaMsgFactory;
import io.apicurio.tests.utils.Constants;
import io.kiota.http.vertx.VertXRequestAdapter;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@Tag(Constants.MIGRATION)
public class GenerateCanonicalHashImportIT extends ApicurioRegistryBaseIT {

    @Test
    public void testGeneratingCanonicalHashOnImport() throws Exception {
        var adapter = new VertXRequestAdapter(VertXAuthFactory.defaultVertx);
        adapter.setBaseUrl(ApicurioRegistryBaseIT.getRegistryV3ApiUrl());
        RegistryClient client = new RegistryClient(adapter);

        Map<String, String> artifacts = new HashMap<>();

        JsonSchemaMsgFactory jsonSchema = new JsonSchemaMsgFactory();
        for (int i = 0; i < 20; i++) {
            String artifactId = i + "-" + UUID.randomUUID();
            String content = IoUtil.toString(jsonSchema.getSchemaStream());
            artifacts.put(artifactId, content);
        }
        var importReq = client.admin().importEscaped().toPostRequestInformation(generateExportedZip(artifacts));
        importReq.headers.replace("Content-Type", Set.of("application/zip"));
        adapter.sendPrimitive(importReq, new HashMap<>(), Void.class);

        for (var entry : artifacts.entrySet()) {
            String groupId = "default";
            String artifactId = entry.getKey();
            String content = entry.getValue();

            /*
            TODO: Check if the canonical hash is generated correctly.
                  The only way is to generate canonical hash and then search artifact by it. But that needs apicurio-registry-app module as dependency.
             */

            var registryContent = client.groups().byGroupId(groupId).artifacts().byArtifactId(artifactId).versions().byVersionExpression("branch=latest").content().get();
            assertNotNull(registryContent);
            assertEquals(content, IoUtil.toString(registryContent));
        }

    }

    public InputStream generateExportedZip(Map<String, String> artifacts) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8);
            EntityWriter writer = new EntityWriter(zip);

            Map<String, Long> contentIndex = new HashMap<>();

            AtomicInteger globalIdSeq = new AtomicInteger(1);
            AtomicInteger contentIdSeq = new AtomicInteger(1);

            for (var entry : artifacts.entrySet()) {
                String artifactId = entry.getKey();
                String content = entry.getValue();
                byte[] contentBytes = IoUtil.toBytes(content);
                String contentHash = DigestUtils.sha256Hex(contentBytes);

                String artifactType = ArtifactType.JSON;

                Long contentId = contentIndex.computeIfAbsent(contentHash, k -> {
                    ContentEntity contentEntity = new ContentEntity();
                    contentEntity.contentId = contentIdSeq.getAndIncrement();
                    contentEntity.contentHash = contentHash;
                    contentEntity.canonicalHash = null;
                    contentEntity.contentBytes = contentBytes;
                    contentEntity.artifactType = artifactType;

                    try {
                        writer.writeEntity(contentEntity);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    return contentEntity.contentId;
                });

                ArtifactVersionEntity versionEntity = new ArtifactVersionEntity();
                versionEntity.artifactId = artifactId;
                versionEntity.artifactType = artifactType;
                versionEntity.contentId = contentId;
                versionEntity.owner = "integration-tests";
                versionEntity.createdOn = System.currentTimeMillis();
                versionEntity.description = null;
                versionEntity.globalId = globalIdSeq.getAndIncrement();
                versionEntity.groupId = null;
                versionEntity.labels = null;
                versionEntity.name = null;
                versionEntity.state = VersionState.ENABLED;
                versionEntity.version = "1";
                versionEntity.versionOrder = 1;

                writer.writeEntity(versionEntity);

                writer.writeEntity(
                        ArtifactBranchEntity.builder()
                                .artifactId(artifactId)
                                .branchId(BranchId.LATEST.getRawBranchId())
                                .branchOrder(1)
                                .version("1")
                                .build()
                );
            }

            zip.flush();
            zip.close();

            return new ByteArrayInputStream(outputStream.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
