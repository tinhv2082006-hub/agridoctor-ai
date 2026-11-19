import unittest

from usb_demo import physical_device


class UsbDemoTests(unittest.TestCase):
    def test_selects_phone_when_emulator_is_also_running(self):
        listing = 'List of devices attached\nemulator-5554\tdevice\nphone-test\tdevice\n'
        self.assertEqual('phone-test', physical_device(listing))

    def test_unauthorized_or_multiple_phones_do_not_get_a_mapping(self):
        for listing in ('List of devices attached\n',
                        'List of devices attached\nphone-test\tunauthorized\n',
                        'List of devices attached\nphone-a\tdevice\nphone-b\tdevice\n'):
            with self.subTest(listing=listing), self.assertRaises(RuntimeError):
                physical_device(listing)
