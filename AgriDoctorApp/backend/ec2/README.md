# AgriDoctor trên Amazon Linux 2023

Trên EC2 đã có Python 3.12, chuyển thư mục backend qua kênh SSH được phép và chạy `bash backend/ec2/install.sh`. Khi chuyển, chỉ chọn mã nguồn, hai tệp requirements, SQLite kho tri thức và thư mục `ec2`. Giữ khóa SSH, tệp cấu hình bí mật, trạng thái quản trị Windows và model hội thoại trên máy hiện tại.

FastAPI nghe ở `127.0.0.1:8765`. Backend nghiệp vụ nghe ở `127.0.0.1:8766`. Hai dịch vụ systemd chạy bằng tài khoản riêng. Cấu hình và mã truy cập nằm trong `/etc/agridoctor/backend.env`, chỉ root đọc được. Trạng thái hội thoại nằm trong `/var/lib/agridoctor`. Lệnh cài đặt giữ lại mã truy cập đã tạo lần đầu.

```bash
systemctl is-active agridoctor-core agridoctor-api
ss -ltn '( sport = :8765 or sport = :8766 )'
curl -fsS http://127.0.0.1:8765/health
curl -fsS http://127.0.0.1:8765/v1/lookup \
  -H 'Content-Type: application/json' \
  --data '{"crop_id":"rice","symptoms":"bông lúa trắng hết"}'
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8765/v1/vision \
  -H 'Content-Type: application/json' --data '{}'
```

Mong đợi mã 200 cho hai lệnh tra cứu và kiểm tra sức khỏe, mã 401 khi gửi ảnh thiếu token. Khi lỗi, xem `sudo journalctl -u agridoctor-core -u agridoctor-api -n 50`.

## Phân tích ảnh

Backend hiện có Gemini để gợi ý dấu hiệu nhìn thấy trên ảnh JPEG và đối chiếu kho tri thức. Chỉ bật khi chủ dự án cho phép gửi ảnh đến Google và đã xác minh gói/hạn mức của dự án Gemini. Điền `GEMINI_API_KEY` và `GEMINI_MODEL` trong tệp cấu hình root, sau đó khởi động lại `agridoctor-core`. Thử bằng ảnh được phép sử dụng của từng cây và so sánh dấu hiệu trả về với nhãn đã được duyệt. Mô hình Qwen3 trong dự án phục vụ hội thoại. Để chạy nhận diện ảnh bằng CPU, cần model ảnh, nhãn và tập kiểm thử tương ứng.

## Hội thoại Qwen3 trên EC2

Dịch vụ `agridoctor-llama.service` chạy bản CPU llama.cpp với model Qwen3-4B Q4_K_M tại `/opt/agridoctor/llama`. Cài bộ chạy và model đã xác minh mã băm trước khi bật dịch vụ. `llama-server` chỉ nghe `127.0.0.1:8767`, dành một lượt sinh đồng thời và dùng hai luồng CPU.

Cho `agridoctor-core` đọc cùng tệp `/etc/agridoctor/llama.env` chỉ root đọc được, chứa `LLAMA_API_KEY` ngẫu nhiên gồm 64 ký tự hex. Đặt thêm `AGRIDOCTOR_LLAMA_PORT=8767` trong cấu hình systemd của core. Xác minh model sẵn sàng trước khi xuất bản cấu hình chat `engine=local`. Sau đó kiểm tra `/v1/chat` trả `mode` là `AI cục bộ · Qwen3-4B`. Tính năng ảnh `/v1/vision` dùng cấu hình Gemini riêng.

Để cho phép APK HTTPS chọn Qwen3 hoặc Gemini theo cuộc chat, đặt `AGRIDOCTOR_CHAT_ENGINE_SELECTION=1` trong cấu hình systemd của core sau khi đã cấu hình cả hai bộ trả lời. Hạn mức và trạng thái bật/tắt chat vẫn lấy từ cấu hình đã xuất bản.

## Kết nối app Kotlin

Phiên riêng tư có thể kiểm thử API từ laptop bằng đường hầm SSH khi đường SSH được cấp quyền.

```powershell
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:8765:127.0.0.1:8765 -i .\agridocter-ai-key.pem ec2-user@<EC2_PUBLIC_IP>
```

APK hiện tại yêu cầu HTTPS nếu dùng Bearer token. Chế độ USB HTTP debug chỉ phù hợp với máy chủ phát triển đã bật chế độ cục bộ riêng. Sau khi có URL HTTPS được phê duyệt, đặt `-PAGRIDOCTOR_SERVICE_URL=https://<ten-mien>` khi build Android và nhập mã truy cập vào app dành cho thiết bị thử. App dùng các tuyến `/v1/vision`, `/v1/transcribe` và `/v1/chat` với `Authorization: Bearer <token>`. Giữ token và khóa Gemini ngoài APK. Trước khi mở cho người dùng, cần cổng HTTPS, xác thực theo người dùng và hạn mức tại cổng.

## Theo dõi chi phí

Theo dõi EC2, EBS và IPv4 trong AWS Billing và đặt cảnh báo AWS Budgets phù hợp với hạn mức dự án. Dừng EC2 khi kết thúc phiên dùng nếu dự án cho phép gián đoạn. Khi dừng, phí điện toán EC2 ngừng và EBS vẫn có thể được tính phí. [Vòng đời EC2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-lifecycle.html)
