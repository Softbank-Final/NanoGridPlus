package org.brown.nanogridplus.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SQS 메시지로 수신하는 작업 요청 DTO
 *
 * JSON 스키마 예시:
 * {
 *   "requestId": "uuid-string",
 *   "functionId": "func-01",
 *   "runtime": "python",
 *   "s3Bucket": "code-bucket-name",
 *   "s3Key": "func-01/v1.zip",
 *   "timeoutMs": 5000
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskMessage {

    @JsonProperty("requestId")
    private String requestId;

    @JsonProperty("functionId")
    private String functionId;

    @JsonProperty("runtime")
    private String runtime;  // "python", "cpp" 등

    @JsonProperty("s3Bucket")
    private String s3Bucket;

    @JsonProperty("s3Key")
    private String s3Key;

    @JsonProperty("timeoutMs")
    private int timeoutMs;

    @JsonProperty("memoryMb")
    private Integer memoryMb;  // 할당된 메모리 (MB), 없으면 null

    @Override
    public String toString() {
        return String.format(
                "TaskMessage[requestId=%s, functionId=%s, runtime=%s, s3Bucket=%s, s3Key=%s, timeoutMs=%d, memoryMb=%s]",
                requestId, functionId, runtime, s3Bucket, s3Key, timeoutMs, memoryMb
        );
    }
}

