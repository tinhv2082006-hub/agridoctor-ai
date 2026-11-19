# Kho tri thức AgriDoctor AI v1.3

Bản đóng gói Android 0.3.0 ghi nhận 80 mục được chủ dự án duyệt sử dụng ngày 18/11/2026. Trường `approval_scope=project` mô tả phạm vi quyết định này. Duyệt chuyên môn là bước riêng. Trạng thái chờ duyệt bên dưới mô tả dữ liệu khởi tạo v1.3 trước khi chủ dự án duyệt.

Bản này có 80 tình huống cho sầu riêng, cà phê và lúa nước. Kho bổ sung 18 mục bệnh gồm 8 mục lúa, 6 mục cà phê và 4 mục sầu riêng. Mỗi mục dẫn đến phần mô tả triệu chứng trong tài liệu. Nguồn nước ngoài dùng để đối chiếu dấu hiệu đã được ghi nhận ở vùng nghiên cứu. Các mục mới đang chờ duyệt chuyên môn. Chế độ mặc định của công cụ dòng lệnh chỉ truy xuất mục đã duyệt. Bật demo để thử dữ liệu nội bộ.

## Chạy trên Windows PowerShell

    $env:PYTHONIOENCODING = 'utf-8'
    python lookup.py rice "bông bạc" --demo
    python lookup.py rice "ruộng không có rầy nâu" --demo
    python lookup.py rice "cháy bìa lá" --region "Tiền Giang" --demo
    python lookup.py coffee "thiếu kali" --growth-stage "cây giống" --demo

## Gọi từ FastAPI

    from pathlib import Path
    from lookup import lookup

    result = lookup(
        Path(__file__).with_name("agridoctor.sqlite"),
        "coffee",
        "lá già cháy mép",
        demo=True,
        crop_type="robusta",
        growth_stage="nursery",
        plant_part="lá",
    )

Khi triển khai, để demo=False và điều khiển cờ này ở backend. Ứng dụng có thể dùng reason và question_field để quyết định hỏi gì tiếp. status vẫn là need_more_info. Truyền crop_type, growth_stage, plant_part và region khi đã hỏi người dùng. Loại cà phê và một số giai đoạn có mã chuẩn và cách gọi tiếng Việt. Bộ lọc bộ phận dựa vào cột organs. Quy tắc phạm vi bắt buộc hiện áp dụng cho mục cà phê chè và các mục dinh dưỡng ở cây giống. Vùng ưu tiên nguồn phù hợp khi có thông tin xuất xứ. Nguồn Tiền Giang có thể tham khảo cho nhận diện triệu chứng, chưa đại diện cho khuyến cáo của mọi vùng.

Từ khóa có dấu và không dấu cùng dùng được. Mã mẫu nhận một số kiểu phủ định và câu chưa chắc chắn. Các quy tắc còn giới hạn với câu phức tạp, lỗi giọng nói thành văn bản và cách nói ngoài danh mục. Trước khi dùng cho nông hộ, cần kiểm thử thêm câu thực tế và chuyên gia duyệt nội dung theo vùng.

## Tệp và nâng cấp

- agridoctor.sqlite — bản dùng trực tiếp, schema 2.
- agridoctor.sql — bản dựng lại SQLite.
- catalog.json — bản xuất đầy đủ.
- extend_catalog_v13.py — tạo kho v1.3 từ thư mục chứa SQLite và JSON của v1.2, với tham số thứ hai là thư mục đầu ra mới.
- lookup.py — CLI và hàm tra cứu.
- README.md — tài liệu này.

Dùng lookup.py với SQLite schema 2. Mã tình huống cũ được giữ. Nếu bạn đã thay đổi dữ liệu hay trạng thái duyệt ở bản riêng, sao lưu và nhập có chọn lọc theo mã. Với cơ sở dữ liệu chỉnh sửa từ v1.1, cần áp dụng các bảng mới case_scope và source_region trước khi dùng hàm tra cứu. Khi nâng cấp app Android, bản v1.3 được mở từ tệp SQLite riêng để thiết bị đã cài v1.2 nhận kho mới.

Khi duyệt một mục, ghi review_status='approved', reviewed_by và reviewed_on (YYYY-MM-DD). Đối chiếu mục tham chiếu, bối cảnh và cách nói. Nguồn cho phần bổ sung v1.3 được đối chiếu ngày 14/11/2026.
