package org.brown.nanogridplus.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.config.AgentConfig;
import org.brown.nanogridplus.config.AgentProperties;
import org.brown.nanogridplus.model.TaskMessage;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * S3 기반 코드 저장소 서비스 구현
 *
 * S3에서 코드 zip을 다운로드하고 작업 디렉터리에 압축 해제
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3CodeStorageService implements CodeStorageService {

    private final S3Client s3Client;
    private final AgentProperties agentProperties;
    private final AgentConfig agentConfig;

    @Override
    public Path prepareWorkingDirectory(TaskMessage taskMessage) {
        String requestId = taskMessage.getRequestId();
        String s3Bucket = determineS3Bucket(taskMessage);
        String s3Key = taskMessage.getS3Key();

        log.info("Preparing working directory for request: {}", requestId);
        log.info("  - S3 Bucket: {}", s3Bucket);
        log.info("  - S3 Key: {}", s3Key);

        try {
            // 1. 작업 디렉터리 생성
            Path workingDir = createWorkingDirectory(requestId);

            // 2. S3에서 zip 다운로드
            Path zipFilePath = downloadFromS3(s3Bucket, s3Key, workingDir, requestId);

            // 3. zip 압축 해제
            extractZipFile(zipFilePath, workingDir, requestId);

            // 4. zip 파일 삭제 (압축 해제 후 불필요)
            Files.deleteIfExists(zipFilePath);

            log.info("Successfully prepared working directory: {}", workingDir);
            return workingDir;

        } catch (Exception e) {
            String errorMsg = String.format(
                    "Failed to prepare working directory for requestId=%s, s3Bucket=%s, s3Key=%s",
                    requestId, s3Bucket, s3Key
            );
            log.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * S3 버킷 이름 결정
     * 우선순위 1: TaskMessage에 포함된 s3Bucket
     * 우선순위 2: AgentProperties의 기본 codeBucketName
     */
    private String determineS3Bucket(TaskMessage taskMessage) {
        String bucket = taskMessage.getS3Bucket();
        if (bucket == null || bucket.trim().isEmpty()) {
            bucket = agentProperties.getS3().getCodeBucketName();
            log.debug("Using default S3 bucket from config: {}", bucket);
        }
        return bucket;
    }

    /**
     * 작업 디렉터리 생성
     * 경로: {taskBaseDir}/{requestId}
     */
    private Path createWorkingDirectory(String requestId) throws IOException {
        String baseDir = agentConfig.getTaskBaseDir();
        Path workingDir = Paths.get(baseDir, requestId);

        // 디렉터리가 이미 존재하면 삭제 후 재생성 (깨끗한 상태 보장)
        if (Files.exists(workingDir)) {
            log.debug("Working directory already exists, cleaning: {}", workingDir);
            deleteDirectory(workingDir);
        }

        Files.createDirectories(workingDir);
        log.debug("Created working directory: {}", workingDir);

        return workingDir;
    }

    /**
     * S3에서 zip 파일 다운로드
     */
    private Path downloadFromS3(String bucket, String key, Path workingDir, String requestId) {
        Path zipFilePath = workingDir.resolve("code.zip");

        log.info("Downloading from S3: s3://{}/{} -> {}", bucket, key, zipFilePath);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try {
            s3Client.getObject(getObjectRequest, ResponseTransformer.toFile(zipFilePath));
            log.info("Successfully downloaded zip file: {} bytes", Files.size(zipFilePath));
            return zipFilePath;

        } catch (Exception e) {
            String errorMsg = String.format(
                    "Failed to download from S3: s3://%s/%s for requestId=%s",
                    bucket, key, requestId
            );
            log.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * zip 파일 압축 해제
     * zip 내의 디렉터리 구조를 유지하면서 추출
     */
    private void extractZipFile(Path zipFilePath, Path targetDir, String requestId) throws IOException {
        log.info("Extracting zip file: {} -> {}", zipFilePath, targetDir);

        int extractedFiles = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipFilePath))) {
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path targetPath = targetDir.resolve(entry.getName());

                // 디렉터리 순회 공격 방지: targetPath가 targetDir 밖을 가리키는지 확인
                if (!targetPath.normalize().startsWith(targetDir.normalize())) {
                    log.warn("Suspicious zip entry detected, skipping: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    // 부모 디렉터리 생성
                    Files.createDirectories(targetPath.getParent());

                    // 파일 추출
                    Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    extractedFiles++;
                }

                zipInputStream.closeEntry();
            }
        }

        log.info("Successfully extracted {} files from zip for requestId={}", extractedFiles, requestId);

        if (extractedFiles == 0) {
            log.warn("No files extracted from zip file. Empty archive? requestId={}", requestId);
        }
    }

    /**
     * 디렉터리 재귀적 삭제
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            paths.sorted((p1, p2) -> -p1.compareTo(p2))  // 역순으로 정렬 (파일 먼저, 디렉터리 나중)
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });
        }
    }
}

