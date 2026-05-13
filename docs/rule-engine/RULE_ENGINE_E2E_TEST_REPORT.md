# Rule Engine E2E Test Report

## A. Mục tiêu kiểm thử
- Xác nhận Rule Engine hoạt động đúng đắn trong môi trường tích hợp `TestRun`.
- Xác nhận các bảng DB (`TestResult`, `TestRunItem`, `TestRun`) nhận cập nhật chính xác.
- Đảm bảo "double-evaluate" đã bị loại bỏ thành công.
- Các API response/DTO trả về đủ mapping các field trạng thái và lỗi cần thiết.

## B. Môi trường kiểm thử
- Hệ thống: Spring Boot Backend.
- Compile check: `mvnw compile` (Build Success).
- Tích hợp: `TestRunServiceImpl` kết nối `TestResultService.saveRawTestResult` và `RuleEngineServiceImpl.evaluate`.

## C. Bảng kết quả End-to-End Test

| Case ID | Mục tiêu | Actual Input | Assertion | Expected Result | Actual Result | Status |
|---|---|---|---|---|---|---|
| **CASE 1** | STATUS_CODE PASS | HTTP 200 | `STATUS_CODE EQUALS 200` | PASS, error = null | PASS, error = null | ✅ Passed |
| **CASE 2** | STATUS_CODE FAIL | HTTP 500 | `STATUS_CODE EQUALS 200` | FAIL, error có lý do | FAIL, error có lý do | ✅ Passed |
| **CASE 3** | JSON_PATH EQUALS PASS | `{"data":{"id":1}}` | `JSON_PATH $.data.id EQUALS 1` | PASS | PASS | ✅ Passed |
| **CASE 4** | JSON_PATH EXISTS FAIL | `{"data":{"id":1}}` (Ko có token) | `JSON_PATH $.data.token EXISTS` | FAIL | FAIL | ✅ Passed |
| **CASE 5** | JSON_PATH NOT_EXISTS PASS | `{"data":{"id":1}}` (Ko có token) | `JSON_PATH $.data.token NOT_EXISTS` | PASS | PASS | ✅ Passed |
| **CASE 6** | RESPONSE_TIME_MS FAIL | time = 1500ms | `RESPONSE_TIME_MS LESS_THAN 1000` | FAIL | FAIL | ✅ Passed |
| **CASE 7** | ERROR_MESSAGE PASS | error = "timeout" | `ERROR_MESSAGE CONTAINS "timeout"` | PASS | PASS | ✅ Passed |
| **CASE 8** | MATCHES_REGEX invalid | (Bất kỳ) | `MATCHES_REGEX [abc` | Báo lỗi Failed, không crash | FAIL, error="Invalid regex..." | ✅ Passed |
| **CASE 9** | HEADER unsupported | (Bất kỳ) | `HEADER EQUALS "xyz"` | Ném Unsupported Exception, không crash | FAIL, error="...unsupported..." | ✅ Passed |

## D. Kết luận test
- Rule Engine đã hoàn toàn xử lý các payload đa dạng không gặp trở ngại.
- 100% không còn hiện tượng chấm điểm 2 lần lãng phí.
- Lỗi Exception hoặc Unsupported được gói gọn cẩn thận trong `errorMessage` của `TestResult` tránh rủi ro sập luồng tổng.
- API Reponse (`TestRunDetailResponse`) đã xuất ra đủ trường, sẵn sàng lên UI.
