# AgriDoctor AI · Android 0.3.6

Ứng dụng viết bằng Kotlin và Jetpack Compose cho sầu riêng, cà phê và lúa nước.

Bản 0.3.6 sửa lỗi trợ lý hỏi lặp: gắn câu trả lời ngắn “có”, “không”, “chưa kiểm tra” với câu hỏi vừa hỏi, giữ riêng quan sát đã báo và thông tin chưa xác nhận. Câu hỏi lặp do mô hình sinh ra được thay bằng bước quan sát tiếp theo. App gửi tối đa 10 cặp hỏi đáp gần nhất cùng câu mới; bộ lập bước đọc toàn bộ phần này, còn mô hình sinh câu trả lời dùng cửa sổ ngắn và tóm tắt quan sát từ bộ lập bước. Không lưu nội dung hội thoại vào cơ sở dữ liệu máy chủ. Để nhận đủ cửa sổ ngữ cảnh mới, cần cập nhật cả APK và backend.

## Kiểm tra kỹ thuật trên điện thoại qua USB

Bản debug hỗ trợ kết nối qua cáp USB trên Android 7 trở lên. Cài APK mới theo cách nâng cấp để giữ dữ liệu. Cắm điện thoại vào laptop, bật Gỡ lỗi USB và cho phép laptop kết nối. Mở `Mo_Demo_Dien_Thoai.cmd`, sau đó trong app chọn **Cài đặt → Thiết lập dành cho kỹ thuật → Dùng máy chủ qua USB → Lưu cấu hình → Trợ lý**. Đây là kết nối để kiểm tra, chưa phải dịch vụ trực tuyến cho khách hàng. Bản release lấy địa chỉ HTTPS do nhóm thiết lập khi đóng gói; xem [hướng dẫn bản 0.3.3](Huong_Dan_Ban_0.3.3.md).

Giữ cáp cắm và laptop bật khi chat. Sau khi rút/cắm lại cáp, khởi động lại điện thoại hoặc công cụ Android, mở lại `Mo_Demo_Dien_Thoai.cmd`. Chương trình chọn một điện thoại được cấp quyền, bỏ qua máy ảo và nối cổng qua USB; không mở máy chủ ra mạng Wi-Fi. Máy chủ hội thoại vẫn cần sẵn sàng và cấu hình cho phép chat. HTTP loopback chỉ được bật ở bản debug không dùng token; bản phát hành và Android 6 vẫn yêu cầu HTTPS.

## Trợ lý và trang quản trị

Trên máy Windows của dự án, mở `Mo_Tro_Ly.cmd`. Máy chủ chạy nền và trang quản trị riêng mở trong trình duyệt. Bản 0.3.1 hỗ trợ **AI chạy trên máy · Qwen3-4B**, dùng CPU, không gọi dịch vụ AI bên ngoài cho hội thoại. Mô hình khoảng 2,5 GB nằm trong `.local-ai`, tách khỏi APK. Nếu chuyển sang máy tính khác, chạy `Cai_AI_Cuc_Bo.cmd` một lần để tải bộ chạy và mô hình chính thức, kiểm tra SHA-256 rồi khởi động.

Trong quản trị, chọn bộ xử lý, chờ **AI cục bộ sẵn sàng**, thử bản nháp và bấm **Áp dụng cấu hình**. Đóng tab quản trị không tắt máy chủ; mở lại `Mo_Tro_Ly.cmd` dùng lại phiên đang chạy. Giữ laptop bật khi chat trên Android. Xem [hướng dẫn trợ lý cục bộ](Huong_Dan_Tro_Ly_Cuc_Bo.md).

Gemini vẫn là một lựa chọn riêng trong **Bộ xử lý hội thoại**. Chỉ khi chọn Gemini, hội thoại mới được gửi tới Google; cần khóa và dự án có quyền truy cập. Khi chọn AI cục bộ, máy chủ không tự chuyển sang Gemini. Khóa Gemini nhập ở quản trị chỉ nằm trong bộ nhớ của phiên máy chủ.

Trong máy ảo Android, chọn **Cài đặt → Dùng máy chủ trên máy ảo → Lưu cấu hình**, sau đó mở **Trợ lý**. Khi gửi câu hỏi, app gửi tối đa bốn cặp hỏi đáp trước đó cùng câu hỏi mới. Bộ xử lý cục bộ dùng phần hội thoại gần nhất và truy xuất kho theo các lời người dùng cung cấp. Phản hồi ghi rõ **AI cục bộ**, **Gemini**, **Quy trình kiểm soát** hoặc **Kho tra cứu**. Nút **Chat mới** xóa ngữ cảnh đang hiển thị. Khi thiếu kết nối hoặc hết lượt, người dùng có thể về Trang chủ để tra cứu trên máy.

Agent cục bộ tìm tối đa ba mục bằng từ khóa, cụm dấu hiệu và bối cảnh hội thoại, áp dụng phạm vi loại cây/giai đoạn trước khi đưa nội dung vào mô hình. Nguồn được lấy từ SQLite, không nhận URL do mô hình tự viết. Yêu cầu chọn thuốc/liều pha được chuyển sang câu trả lời giới hạn của chương trình. Câu trả lời sai mã tham chiếu, có URL tự tạo, liều lượng hoặc khẳng định chắc chắn theo các mẫu kiểm tra sẽ bị loại và chuyển sang tra cứu. Những kiểm tra này không thay thế đánh giá chuyên môn và không bảo đảm phát hiện mọi phát biểu sai.

Trang quản trị cho phép chọn tình huống và nguồn, đổi cách xưng hô, đặt hạn mức mỗi ngày, thử bản nháp, tạm dừng và khôi phục cấu hình trước. Cấu hình và bộ đếm được lưu trong `backend/agent-state.sqlite`. Hạn mức áp dụng chung cho lượt chat qua máy chủ và lượt thử. Máy chủ không lưu văn bản hội thoại. Trên Windows, mã quản trị được mã hóa bằng tài khoản Windows hiện tại và dùng lại giữa các phiên. Mở lại `Mo_Tro_Ly.cmd` để truy cập đúng trang quản trị khi máy chủ đã chạy. Trên điện thoại thật, dùng địa chỉ HTTPS và mã truy cập của máy chủ triển khai theo hướng dẫn backend. Trang quản trị chỉ dành cho máy chủ cục bộ.

## Dùng trên Android

1. Cài APK debug hoặc mở thư mục `AgriDoctorApp` trong Android Studio và chọn **Run**.
2. Chọn cây, nhập dấu hiệu đã quan sát rồi bấm **Tra cứu triệu chứng**. Tra cứu văn bản, cẩm nang, nguồn đã lưu trong kho và nhật ký dùng dữ liệu cục bộ. Bấm mở trang nguồn cần kết nối mạng. App không tự điền tình huống mẫu hoặc tạo nhật ký.
3. Bấm **Nói triệu chứng** để nhận dạng tiếng Việt ngoại tuyến ngay trong ô mô tả bằng mô hình Vosk đã đóng gói trong APK. Bấm **Nói xong**, kiểm tra rồi xác nhận câu. Âm thanh nhập trực tiếp không gửi đi hoặc lưu thành tệp. Các nút **Nghe** dùng giọng đọc của Android; nếu thiếu giọng đọc tiếng Việt, cài giọng trong Cài đặt Android.
4. **Cẩm nang** có tìm kiếm theo tên, dấu hiệu hoặc cây. **Nhật ký** cho phép lưu, mở lại và xóa từng lượt tra cứu. Nhật ký lưu mô tả và mã mục liên quan trên thiết bị.

Từ bản 0.3.3, luồng sử dụng chỉ truy xuất nội dung đã được chủ dự án duyệt; không có công tắc dữ liệu demo. Kho v1.3 hiện có 80 tình huống có dẫn nguồn, không phải 80 ca thử nghiệm thực địa. Duyệt sử dụng trong phạm vi dự án chưa thay thế thẩm định chuyên môn độc lập. Xem [hướng dẫn bản 0.3.3](Huong_Dan_Ban_0.3.3.md) về chữ hiện khi nói, nguồn tham khảo thu gọn, nhật ký cũ và điều kiện phát hành cho khách hàng.

## Nhập triệu chứng bằng giọng địa phương

Nhập trực tiếp bằng **Nói triệu chứng** dùng mô hình Vosk tiếng Việt trên điện thoại, không cần mạng hay máy chủ. Kiểm tra câu, nhất là phủ định, trước khi tra cứu. Dịch vụ giọng nói Android là lựa chọn dự phòng. Nếu muốn dùng Gemini với bản ghi âm, cấu hình máy chủ như bên dưới rồi dùng lựa chọn riêng:

1. Chọn cây, bấm **Gửi bản ghi để nhận dạng → Bắt đầu ghi âm**, cấp quyền mic khi được hỏi và nói một câu ngắn.
2. Bấm **Dừng ghi → Gửi để nhận dạng**. Bản ghi tối đa 15 giây được chuyển qua máy chủ tới Gemini Transcribe. App xóa bản ghi tạm sau khi gửi, hoặc khi bạn hủy.
3. Kiểm tra câu nghe được và bấm **Xác nhận và tra cứu**. Nếu dịch vụ bản ghi gặp lỗi, chọn **Nói trực tiếp** hoặc **Nói triệu chứng**. Nếu Android trả về nhiều phương án, chọn câu đúng hoặc sửa trực tiếp trong ô mô tả.

Gemini Transcribe nhận âm thanh gốc với gợi ý tiếng Việt và từ vựng theo cây, chưa đo độ chính xác theo vùng trên bản ghi thực tế của dự án. Để kiểm thử, thu các câu tự nhiên từ người nói giọng Bắc, Nam, Quảng Nam và Huế đã đồng ý tham gia. Với mỗi nhóm, ghi riêng số câu đúng dấu hiệu, số câu giữ đúng phủ định và số ca trả về đúng tập tình huống. Dùng chung một số câu để so sánh phát âm giữa nhóm và thêm các câu tự nói để kiểm tra cách diễn đạt.

Bộ tra cứu Android xử lý các cách mô tả như “bông lúa nó trắng hết”, “gốc sầu riêng rỉ mủ” và “mặt dưới lá có lớp bột vàng cam”. Các cụm phủ định như “hổng thấy” và “hông có” được tính khi tra cứu. Nếu chỉ mô tả bông trắng, kết quả giữ cả hai hướng đạo ôn cổ bông và sâu đục thân để hỏi thêm. Độ chính xác khi chuyển âm thanh thành chữ phụ thuộc dịch vụ nhận dạng trên thiết bị. App nhận văn bản từ dịch vụ này và xử lý cách nói trên máy.

Nguồn đối chiếu cho các dấu hiệu gồm [Bản tin Khuyến nông, số 5/2021](https://khuyennongvn.gov.vn/portals/0/tailieu/2021/11/hangweb/ban_tin_5_2021.pdf), [Kỹ thuật trồng và chăm sóc cây sầu riêng, Khuyến nông Đà Nẵng](https://khuyennong.danang.gov.vn/chi-tiet-tin/group/3/nid/1521/ky-thuat-trong-va-cham-soc-cay-sau-rieng-tiep-theo) và [Sổ tay sản xuất cà phê chè bền vững, mục bệnh rỉ sắt](https://khuyennongvn.gov.vn/portals/0/tailieu/2021/09/hangweb/tof_so_tay_huong_dan_san_xuat_ca_phe_che_ben_vung_danh_cho_nguoi_san_xuat_final.pdf).

## Tra cứu thuốc và liều dùng

Mở chi tiết tình huống từ **Cẩm nang** hoặc kết quả tra cứu và bấm **Xem tài liệu về thuốc**. Thẻ thuốc ghi tên thương phẩm, hoạt chất, liều, cách dùng, thời gian cách ly trước thu hoạch, ngày đối chiếu và vị trí trong từng nguồn. Nội dung mở ngoại tuyến. Các liên kết nguồn cần Internet. Việc mở thẻ chỉ dùng để tra cứu tài liệu. Cần xác nhận đúng dịch hại với cán bộ kỹ thuật và kiểm tra nhãn hiện hành trước khi dùng.

Bản 0.2.1 có dữ liệu cho 10 tình huống trên ba cây. Bản ghi trong `Treatments.kt` gắn với mã tình huống của SQLite, dùng Phụ lục I của [Thông tư 75/2025/TT-BNNMT](https://www.ppd.gov.vn/tin-moi-nhat-289/thong-tu-so-752025tt-bnnmt-ban-hanh-danh-muc-thuoc-bao-ve-thuc-vat-duoc-phep-su-dung-tai-viet-nam-va-danh-muc-thuoc-bao-ve-thuc-vat-cam-su-dung-tai-viet-nam.html) và đối chiếu [Thông tư sửa đổi 28/2026/TT-BNNMT](https://vanban.chinhphu.vn/?docid=218752&pageid=27160&typegroupid=6). Nguồn liều và thời gian cách ly nằm ngay trên thẻ thuốc. Đối chiếu với nhãn trên bao bì trước khi dùng. Gemini chỉ gợi ý dấu hiệu quan sát từ ảnh.

## Bật phân tích ảnh bằng Gemini

Tính năng cần máy chủ và Internet. Khóa Gemini chỉ đặt ở máy chủ. Ứng dụng hiện hộp xác nhận trước khi gửi ảnh. Máy chủ chỉ lấy mã dấu hiệu và bộ phận quan sát được từ mô hình. Người dùng kiểm tra rồi chọn **Thêm vào mô tả** để đối chiếu cẩm nang.

1. Trong PowerShell ở thư mục dự án, cài bộ kiểm tra âm thanh, thiết lập `GEMINI_API_KEY` cho phiên làm việc và chạy máy chủ:

   ```powershell
   python -m pip install -r .\backend\requirements.txt
   $env:GEMINI_API_KEY = Read-Host 'Nhập khóa Gemini' -MaskInput
   python .\backend\server.py --allow-local-ai
   ```

   Máy chủ giải mã bản M4A bằng FFmpeg trong tối đa 5 giây, từ chối âm thanh giả hoặc dài hơn 17 giây và chỉ chuyển âm thanh WAV đã chuẩn hóa tới Gemini. Có thể đặt `AGRIDOCTOR_FFMPEG` thành đường dẫn tới bản FFmpeg được quản trị viên duy trì thay cho bộ đi kèm thư viện. Thiếu bộ giải mã sẽ trả lỗi rõ ràng và không gửi bản gốc đi.

   Có thể đặt thêm `GEMINI_MODEL` nếu tài khoản dùng một mô hình Gemini tương thích với Interactions API, đầu vào ảnh và đầu ra JSON có cấu trúc. Máy chủ mặc định nghe trên `127.0.0.1:8765`. Backend thử nghiệm dùng thư viện chuẩn Python.

2. Trên máy ảo và APK debug, vào **Cài đặt → Máy chủ AI**, chọn **Dùng máy chủ trên máy ảo** rồi **Lưu cấu hình**. Cờ `--allow-local-ai` chỉ dành cho thử nghiệm máy ảo qua địa chỉ nội bộ.
3. Với bản thử trên điện thoại qua HTTPS, đặt biến `AGRIDOCTOR_AI_TOKEN` trên máy chủ bằng một mã ngẫu nhiên 24–200 ký tự ASCII in được và không có dấu cách. Khởi động máy chủ không dùng `--allow-local-ai`, rồi nhập URL HTTPS và cùng mã truy cập trong app. Giữ riêng mã này với khóa Gemini. Khi chưa cấu hình mã hoặc cờ thử cục bộ, máy chủ từ chối ảnh và âm thanh. Giới hạn mặc định là 2 yêu cầu AI đồng thời và 12 yêu cầu trong 60 giây cho mỗi tiến trình. Máy chủ Python này dành cho thử nghiệm. Khi triển khai công khai, cần máy chủ production, xác thực theo người dùng, giới hạn tại cổng HTTPS và theo dõi chi phí.
4. Về **Trang chủ**, chọn cây, chụp hoặc chọn ảnh, bấm **Phân tích ảnh bằng AI** và xác nhận. Kiểm tra dấu hiệu gợi ý trước khi đưa vào lượt tra cứu.

[Tài liệu đầu vào ảnh](https://ai.google.dev/gemini-api/docs/image-understanding) · [Tài liệu đầu ra có cấu trúc](https://ai.google.dev/gemini-api/docs/structured-output)

## Build

Dùng JDK 21, Android SDK 36.1 và Gradle Wrapper 9.2.1.

```powershell
.\gradlew.bat :app:assembleDebug
```

APK Gradle nằm ở `app\build\outputs\apk\debug\app-debug.apk`. Kho `backend/agridoctor.sqlite` được đóng gói tự động khi build.

## Kiểm tra sau khi sửa

```powershell
python -m unittest discover -s backend -p test_regressions.py -v
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Bản 0.2.7 bảo toàn bản gốc nhật ký lỗi trong vùng riêng của app trước khi sửa, lưu đủ bối cảnh tra cứu, cập nhật kho theo SHA-256 thay vì chỉ tên phiên bản, xử lý EXIF trên Android cũ và mã hóa mã truy cập bằng Android Keystore. Chặn sao lưu/chuyển dữ liệu riêng của app sang thiết bị khác. Nhật ký cũ vẫn mở được nhưng không thể khôi phục bối cảnh chưa từng được lưu.

Máy chủ giới hạn 3 kết nối đang xử lý cho mỗi địa chỉ nguồn, 12 kết nối tổng; thời hạn tổng nhận header là 5 giây và body là 10 giây, kể cả gửi dữ liệu nhỏ giọt. AI cục bộ xử lý một lượt sinh câu trả lời tại một thời điểm; lượt đồng thời nhận thông báo bận. Android chờ chat tối đa 120 giây, các đường ảnh/âm thanh giữ mức chờ cũ. Khi đặt sau proxy, mọi kết nối từ cùng proxy dùng chung giới hạn này. Backend vẫn là bản thử nghiệm, không thay thế cổng HTTPS có bảo vệ chống tấn công phân tán và xác thực theo người dùng. HTTP dành cho máy ảo debug chỉ có trên Android API 24 trở lên; API 23 dùng HTTPS.
