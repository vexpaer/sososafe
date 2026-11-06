import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

class VerificationGuardTest(unittest.TestCase):
    def run_guard(self,text):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'fixture.txt';source.write_text(text)
            return subprocess.run([sys.executable,str(Path(__file__).with_name('verify-instrumentation.py')),str(source),str(Path(folder)/'results.xml')],capture_output=True).returncode
    def test_zero_tests_is_failure(self):
        self.assertNotEqual(0,self.run_guard('OK (0 tests)\n'))
    def test_ignored_or_failed_tests_are_failure(self):
        text=''.join(f'INSTRUMENTATION_STATUS: class=Fixture\nINSTRUMENTATION_STATUS: test=case{i}\nINSTRUMENTATION_STATUS_CODE: {-3 if i==0 else 0}\n' for i in range(6))+'OK (6 tests)\n'
        self.assertNotEqual(0,self.run_guard(text))
    def test_six_real_status_results_and_summary_are_required(self):
        text=''.join(f'INSTRUMENTATION_STATUS: class=Fixture\nINSTRUMENTATION_STATUS: test=case{i}\nINSTRUMENTATION_STATUS_CODE: 0\n' for i in range(6))+'OK (6 tests)\n'
        self.assertEqual(0,self.run_guard(text))
        self.assertNotEqual(0,self.run_guard(text.replace('OK (6 tests)','Process crashed')))

if __name__=='__main__':unittest.main()
