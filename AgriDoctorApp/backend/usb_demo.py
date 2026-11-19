"""Connect one authorized physical Android device to the local demo server."""
import os
from pathlib import Path
import shutil
import subprocess

from local_launcher import main as open_assistant


def adb_path():
    roots = [os.getenv('ANDROID_SDK_ROOT'), os.getenv('ANDROID_HOME')]
    if os.getenv('LOCALAPPDATA'):
        roots.append(str(Path(os.environ['LOCALAPPDATA']) / 'Android' / 'Sdk'))
    for root in roots:
        if root:
            candidate = Path(root) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
            if candidate.is_file():
                return str(candidate)
    installed = shutil.which('adb')
    if installed:
        return installed
    raise RuntimeError('Chưa thấy công cụ Android. Cài Android SDK Platform-Tools trước.')


def run_adb(executable, *args):
    result = subprocess.run([executable, *args], capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=20,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode:
        raise RuntimeError('Chưa thao tác được với điện thoại. Kiểm tra cáp USB và quyền gỡ lỗi trên điện thoại.')
    return result.stdout


def physical_device(output):
    phones = []
    unauthorized = False
    for line in output.splitlines():
        parts = line.split()
        if len(parts) < 2 or parts[0].startswith(('List', '*', 'emulator-')):
            continue
        if parts[1] == 'device':
            phones.append(parts[0])
        elif parts[1] == 'unauthorized':
            unauthorized = True
    if not phones:
        if unauthorized:
            raise RuntimeError('Mở khóa điện thoại và chọn Cho phép gỡ lỗi USB, rồi mở lại công cụ này.')
        raise RuntimeError('Chưa thấy điện thoại. Cắm cáp dữ liệu, bật Gỡ lỗi USB rồi mở lại công cụ này.')
    if len(phones) != 1:
        raise RuntimeError('Có nhiều điện thoại đang kết nối. Chỉ giữ điện thoại dùng để demo rồi mở lại.')
    return phones[0]


def main():
    try:
        executable = adb_path()
        phone = physical_device(run_adb(executable, 'devices'))
        sdk = run_adb(executable, '-s', phone, 'shell', 'getprop', 'ro.build.version.sdk').strip()
        if not sdk.isdigit() or int(sdk) < 24:
            raise RuntimeError('Demo USB cần Android 7 trở lên. Thiết bị cũ dùng máy chủ HTTPS.')
        run_adb(executable, '-s', phone, 'reverse', 'tcp:8765', 'tcp:8765')
        mappings = run_adb(executable, '-s', phone, 'reverse', '--list')
        if not any('tcp:8765 tcp:8765' in line for line in mappings.splitlines()):
            raise RuntimeError('Chưa tạo được kết nối USB. Rút cắm lại cáp và thử lại.')
        (Path(__file__).resolve().parent.parent / '.local-ai').mkdir(exist_ok=True)
        open_assistant()
        print('Đã nối điện thoại với máy chủ qua cáp USB.', flush=True)
        print('Trong app: Cài đặt → Dùng máy chủ qua USB → Lưu cấu hình → Trợ lý.', flush=True)
        print('Giữ laptop bật và cáp cắm khi chat. Cắm lại cáp thì mở lại Mo_Demo_Dien_Thoai.cmd.', flush=True)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from error


if __name__ == '__main__':
    main()
