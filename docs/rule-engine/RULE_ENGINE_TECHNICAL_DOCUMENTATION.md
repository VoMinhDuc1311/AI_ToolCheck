# Rule Engine Technical Documentation

## A. Tổng quan Rule Engine
- **Rule Engine là gì**: Đây là bộ phận phân xử kết quả tự động trong ứng dụng AI ToolCheck Backend.
- **Nằm ở đâu**: Nằm ở phần cuối của luồng chạy kiểm thử (TestRun), sau khi HTTP Client đã gửi request và nhận về response thực tế.
- **Tại sao cần Rule Engine**: Để đánh giá API chạy đúng hay sai dựa trên một chuỗi các `TestCaseAssertion` (luật lệ) mà người dùng hoặc AI định nghĩa trước, một cách an toàn và tự động.
- **Single Source of Truth**: `RuleEngineService` là trung tâm duy nhất trên toàn hệ thống quyết định `TestResult.resultStatus` (PASS / FAIL / ERROR).

## B. Kiến trúc liên quan

1. **TestRunServiceImpl**:
    - Điều phối toàn bộ vòng đời chạy test.
    - Gọi test executor để tạo yêu cầu HTTP.
    - Lưu kết quả thô ban đầu thông qua `saveRawTestResult`.
    - Ủy thác cho `RuleEngineService.evaluate` để chấm điểm.
    - Cập nhật `TestRunItem.itemStatus` và `TestRun.runStatus`.

2. **TestResultService**:
    - Giữ vai trò tầng lưu trữ (Data Access / CRUD).
    - Chỉ lưu trữ các giá trị thật của API (`actualResponse`).
    - Không thực hiện đánh giá PASS/FAIL trong luồng xử lý TestRun mới.

3. **RuleEngineService**:
    - Truy xuất danh sách `TestCaseAssertion`.
    - Trích xuất giá trị thực tế (actual value) từ `TestResult`.
    - So sánh giá trị dự kiến (expected value) và giá trị thực tế.
    - Quyết định `ResultStatus` và tổng hợp `errorMessage`.

4. **TestCaseAssertion**:
    - Lưu cấu hình luật lệ bao gồm: `assertionType`, `targetPath` (JsonPath), `operator`, `expectedValue`.

5. **TestResult**:
    - Nơi chứa kết quả thực thi: `actualStatus`, `actualResponseJson`, `responseTimeMs`, `resultStatus`, `errorMessage`.

## C. Flow xử lý chi tiết

1. `HTTP Execute` thực hiện call API.
2. `saveRawTestResult` lưu lại `TestResult` với dữ liệu raw.
3. `RuleEngineService.evaluate(testResultId)` được kích hoạt.
4. Tải lên `TestResult`, `TestRunItem`, `TestCase`.
5. Truy xuất toàn bộ `TestCaseAssertion` (sắp xếp theo `sortOrder`).
6. Với mỗi assertion, trích xuất (extract) giá trị thực tế từ JSON/Status.
7. Thực hiện phép so sánh (compare) bằng `ComparisonOperator`.
8. Tổng hợp đếm số lượng PASS/FAIL.
9. Lưu `TestResult` (cập nhật status và error message).
10. Dựa trên FinalStatus, `TestRunServiceImpl` update `TestRunItem` và `TestRun`.

## D. AssertionType đang hỗ trợ

- `STATUS_CODE`: actual value trích xuất từ `actualStatus`. Ví dụ: `EQUALS 200`.
- `JSON_PATH`: actual value rút từ `actualResponseJson` qua biểu thức targetPath (VD: `$.data.id`).
- `RESPONSE_TIME` / `RESPONSE_TIME_MS`: actual value lấy từ `responseTimeMs`.
- `BODY_CONTAINS`: kiểm tra text thô bên trong `actualResponseJson`.
- `ERROR_MESSAGE`: kiểm tra nội dung `errorMessage` hiện tại.
- `BODY_NOT_NULL`: actual value map thẳng từ `actualResponseJson`.
- `HEADER`: **Hiện tại chưa hỗ trợ (unsupported)** do hệ thống chưa lưu `actualHeadersJson`. Ném lỗi an toàn mà không crash.

## E. ComparisonOperator đang hỗ trợ

- Text/Object so sánh: `EQUALS`, `NOT_EQUALS`, `CONTAINS`, `NOT_CONTAINS`, `IS_NULL`, `IS_NOT_NULL`, `EXISTS`, `NOT_EXISTS`.
- So sánh số học (Sử dụng `BigDecimal` an toàn): `GREATER_THAN`, `GREATER_THAN_OR_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUALS`.
- `MATCHES_REGEX`: Dùng `String.matches()`. Có bắt `PatternSyntaxException` nên regex sai sẽ báo lỗi FAIL/ERROR rõ ràng, hoàn toàn không làm crash hệ thống.

## F. Chính sách PASS / FAIL / ERROR

- **PASS**: Tất cả các điều kiện assertion đều đúng. Quan trọng nhất: `errorMessage` của `TestResult` sẽ bị gán thành `null`.
- **FAIL**: Có ít nhất 1 assertion bị sai. `errorMessage` lưu toàn bộ nguyên nhân (summary) ví dụ: "Expected STATUS_CODE EQUALS 200 but actual was 500".
- **ERROR**: Lỗi xuất phát từ tầng hệ thống mạng (timeout trước HTTP) hoặc logic framework (VD: Regex syntax error, JsonPath format error) không thể chấm điểm như bình thường.

## G. Giới hạn hiện tại
- **HEADER**: Chưa thể dùng được cho tới khi db cập nhật thêm cột `actualHeadersJson`.
- **JSON_PATH**: Nếu targetPath trả về Array hoặc Object, phải cẩn thận khi đối chiếu bằng `EQUALS` với 1 String.
- **ERROR_MESSAGE trực tiếp**: Trong `saveErrorResult`, lỗi mạng sẽ được fail-fast, không chạy vào RuleEngineService do không có JSON nào để chấm.
- **AI Analyze Failure**: Hiện tại dữ liệu đang nằm ở mức nền tảng chờ phase AI Skill 3.

## H. Failure Context cho AI Analyze Failure
Vào phase tới, để AI có thể phân tích lỗi chuẩn xác, Context gửi cho AI sẽ gồm:
- `testRunId`, `testRunItemId`, `testResultId`, `testCaseId`.
- `actualStatus`, `actualResponseJson`, `responseTimeMs`.
- `resultStatus`, `errorMessage`.
- `failedAssertions`: Danh sách các assertion lỗi trích xuất từ DTO chứa `assertionType`, `targetPath`, `operator`, `expectedValue`, `actualValue`, `message`.
- *(Yêu cầu Masking/Xóa token, password nhạy cảm khỏi Request/Response trước khi truyền cho AI).*
