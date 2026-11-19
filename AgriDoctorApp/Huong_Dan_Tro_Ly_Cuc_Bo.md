# Dùng trợ lý AI chạy trên laptop

## Mở và chat

### Điện thoại thật qua cáp USB

Dùng APK debug 0.3.2 trên Android 7 trở lên. Cắm điện thoại vào laptop và cho phép Gỡ lỗi USB. Mở `Mo_Demo_Dien_Thoai.cmd` trên laptop. Trong app mở **Cài đặt → Dùng máy chủ qua USB → Lưu cấu hình**, rồi mở **Trợ lý**. Máy chủ AI trên laptop cần đang chạy và sẵn sàng.

Giữ cáp cắm khi dùng. Mỗi lần rút/cắm lại cáp, mở lại `Mo_Demo_Dien_Thoai.cmd`. Chương trình tạo lại đường nối và dùng lại máy chủ đang chạy; không cần nhập khóa Gemini cho chat Qwen.

### Máy ảo Android

1. Mở `Mo_Tro_Ly.cmd` trong thư mục AgriDoctorApp.
2. Trang **Quản trị trợ lý** mở ra. Chọn **AI chạy trên máy · Qwen3-4B**, bấm **Kiểm tra lại** và chờ thông báo **AI cục bộ sẵn sàng**.
3. Bấm **Áp dụng cấu hình** nếu vừa đổi lựa chọn. Có thể thử hội thoại ngay trong phần **Thử bản nháp**.
4. Trong app Android trên máy ảo, vào **Cài đặt**, chọn **Dùng máy chủ trên máy ảo**, bấm **Lưu cấu hình**, rồi mở **Trợ lý**.

Ví dụ mở đầu: “Ruộng lúa vừa trổ bông, một số bông bạc trắng, tôi nên xem gì trước?” Sau đó trả lời đúng điều đã quan sát. Bấm **Chat mới** để đổi cây hoặc bắt đầu vấn đề khác.

Laptop cần được bật. Mô hình chạy trên laptop; APK không tự chạy mô hình trên điện thoại. Kết nối qua cáp USB và máy ảo là hai lựa chọn thử nghiệm nội bộ. Nếu dùng điện thoại qua mạng thay vì USB, cần máy chủ được triển khai qua HTTPS và mã truy cập theo README. Các công cụ demo hiện không mở máy chủ ra Internet.

## Quản lý

- Bật/tắt **Cho phép người dùng chat**.
- Chọn tình huống và tài liệu được dùng, điều chỉnh cách xưng hô và số lượt mỗi ngày.
- **Gửi thử** dùng bản nháp; **Áp dụng cấu hình** đưa bản nháp vào app.
- **Khôi phục bản trước** lấy lại cấu hình trước lần xuất bản gần nhất.
- Khi AI chưa sẵn sàng, bấm **Khởi động AI trên máy**, đợi vài giây rồi **Kiểm tra lại**.

Nhãn **AI cục bộ · Qwen3-4B** chỉ xuất hiện khi mô hình đã sinh câu trả lời hợp lệ. **Kho tra cứu · AI cục bộ chưa trả lời** là câu trả lời dự phòng. **Quy trình kiểm soát** là phản hồi được chương trình kiểm soát trực tiếp, ví dụ yêu cầu chọn sai cây hoặc hỏi liều pha thuốc.

## Dữ liệu và chi phí

Hội thoại ở chế độ cục bộ không được gửi tới dịch vụ AI bên ngoài. Mô hình không dùng khóa Gemini. Internet cần cho lần tải mô hình và khi người dùng mở liên kết tài liệu. Máy chủ không lưu văn bản hội thoại vào cơ sở dữ liệu; trạng thái cấu hình và bộ đếm được lưu riêng.

Không có phí theo lượt gọi mô hình bên ngoài. Mô hình sử dụng CPU, RAM, điện và khoảng 2,5 GB lưu trữ. Khả năng trả lời và tốc độ phụ thuộc câu hỏi, độ dài hội thoại và tải của laptop.

Kho nội dung do chủ dự án duyệt sử dụng. Việc này không tương đương chuyên gia kiểm chứng mọi gợi ý. Agent hỗ trợ đối chiếu dấu hiệu và bước quan sát tiếp theo; cần tiếp tục đánh giá thực tế.

Ảnh và âm thanh gửi tới Gemini là các chức năng riêng; thay đổi này áp dụng cho chat bằng văn bản.

## Cài lại trên máy khác

Chạy `Cai_AI_Cuc_Bo.cmd` một lần khi có Internet. Bộ cài tải llama.cpp CPU và Qwen3-4B từ nhà phát hành chính thức, kiểm tra SHA-256 trước khi sử dụng. Sau đó mở `Mo_Tro_Ly.cmd` như bình thường.

Phiên bản và mã kiểm tra được ghi trong `.local-ai/manifest.json`. Bộ chạy chỉ nghe trên địa chỉ nội bộ của laptop và yêu cầu mã kết nối riêng. Trên Windows, mã kết nối này và mã quản trị được bảo vệ bằng tài khoản Windows hiện tại.
