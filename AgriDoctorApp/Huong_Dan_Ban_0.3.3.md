# AgriDoctor 0.3.5 — giọng nói, tài liệu và dữ liệu sử dụng

## Nhập bằng giọng nói

“Nói triệu chứng” dùng mô hình tiếng Việt Vosk ngay trên điện thoại. Chữ được cập nhật trong ô Dấu hiệu quan sát theo kết quả nhận dạng tạm; câu có thể được sửa khi có thêm âm thanh. “Nói xong” kết thúc thu âm và lấy câu cuối. Khi ngắt giữa các câu, phần đã nói trước vẫn được giữ lại. “Dừng nghe”, chuyển màn hình hoặc đưa app ra nền hủy phiên đang nghe; kết quả muộn của phiên cũ không được sửa ô nhập.

App không tự gửi câu vừa nói để tra cứu hay lưu nhật ký. Người dùng kiểm tra câu, đặc biệt các từ phủ định, rồi xác nhận. Chữ đã hiện được giữ lại nếu dịch vụ báo lỗi hoặc hết thời gian. Bản ghi gửi tới Gemini là lựa chọn riêng, vẫn cần xác nhận trước khi gửi.

Mô hình `vosk-model-small-vn-0.4` khoảng 32 MB được đóng gói trong APK và kiểm tra SHA-256 khi cài vào vùng riêng của app. Phần nhập trực tiếp không cần Internet, VPS, API key hoặc tải gói tiếng Việt của Android; âm thanh không được lưu thành tệp hoặc gửi lên máy chủ. Lần dùng đầu cần thời gian giải nén và khởi tạo. Cần cấp quyền mic, nói gần điện thoại và kiểm tra lại chữ. Thư viện/mô hình không bảo đảm đúng mọi giọng địa phương hay thuật ngữ nông nghiệp. Xem [mô hình chính thức của Vosk](https://alphacephei.com/vosk/models) và `assets/speech/NOTICE.txt` trong APK.

Nếu nhập trực tiếp gặp lỗi, trạng thái lỗi được giữ dưới ô mô tả. Nút “Thử ứng dụng giọng nói của Android” dùng dịch vụ của hệ điều hành làm lựa chọn riêng; lựa chọn đó có thể cần Internet và chỉ trả chữ sau khi nói xong.

Thanh “Tín hiệu mic” phản ánh mức âm thanh PCM nhận từ mic tích hợp trong lúc nghe. Đây không phải thước đo độ chính xác nhận dạng. Nếu ứng dụng Ghi âm Samsung cũng chỉ thu tiếng ù, cần kiểm tra mic/đường âm thanh của thiết bị trước khi kết luận lỗi chuyển lời nói thành chữ. Bản 0.3.5 đã cài lên Galaxy A23 nhưng chưa xác nhận được một lượt nói thành công trên thiết bị này.

## Câu trả lời của trợ lý

Tên tài liệu và vị trí tham chiếu trước đây hiển thị thành nhiều dòng nhỏ dưới câu trả lời. Bản này thu gọn vào “Nguồn tham khảo (N)”. Mở mục này để xem tên tài liệu, mục/trang tham chiếu và nút mở từng nguồn. Thông tin bộ xử lý cũng nằm trong phần mở rộng. Nguồn vẫn lấy từ kho dữ liệu, không xóa bằng chứng tham khảo.

## Dữ liệu

Luồng sử dụng chỉ tra cứu mục đã được duyệt sử dụng, không có công tắc bật dữ liệu đang chờ duyệt. Kho hiện có 80 tình huống và 36 nguồn; 80 mục mang phạm vi duyệt `project` (chủ dự án), không phải chứng nhận của chuyên gia độc lập. Không sửa trạng thái duyệt để tạo cảm giác đã được kiểm định.

Không tự điền tình huống mẫu, không tự tạo nhật ký. Lượt lưu mới dùng thời gian, mô tả và bối cảnh người dùng nhập. Lượt cũ mang cờ chế độ thử được giữ riêng trong “Xem lượt lưu từ bản cũ”, không đổi thành quan sát thực tế. Một lượt lưu do người dùng tạo vẫn không đồng nghĩa bệnh đã được xác nhận hay đã thử nghiệm ngoài đồng ruộng.

## Kết nối cho khách hàng

Máy chủ là nơi xử lý yêu cầu AI. Bản kiểm tra hiện có máy chủ trên laptop; kết nối USB không phải phương án phát hành cho khách hàng.

Trong giao diện mặc định không hiển thị ô địa chỉ hay hướng dẫn cắm cáp. Các mục này chỉ nằm trong phần kỹ thuật của bản debug. Bản release lấy địa chỉ HTTPS do nhóm phát triển thiết lập lúc đóng gói và bỏ qua địa chỉ USB cũ của bản debug.

Thiết lập URL công khai khi đã có dịch vụ thật:

```powershell
.\gradlew.bat :app:assembleRelease -PAGRIDOCTOR_SERVICE_URL=https://ten-mien-dich-vu-cua-ban
```

Tham số này chỉ chứa địa chỉ công khai. Không đưa khóa Gemini hay khóa dùng chung của máy chủ vào APK. Ví dụ trên không thay thế việc triển khai, xác thực người dùng, ký bản phát hành hoặc kiểm tra dịch vụ thật.

**Hiện chưa có VPS/dịch vụ trực tuyến và chưa triển khai một địa chỉ HTTPS công khai.** Không có thay đổi giao diện nào có thể làm cho AI trên laptop tự trở thành dịch vụ hoạt động độc lập cho khách hàng. Khi chưa có dịch vụ, bản phát hành báo rõ trạng thái và vẫn dùng được tra cứu/cẩm nang trên máy. Cần thiết lập dịch vụ trực tuyến, cơ chế cấp quyền cho người dùng và kiểm thử qua mạng di động trước khi bàn giao sử dụng đại trà. Backend Python hiện tại được dùng cho kiểm tra cục bộ; không mở trực tiếp lên Internet.
