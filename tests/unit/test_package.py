import unittest
from importlib import import_module
from importlib.util import find_spec


class PackageContractTest(unittest.TestCase):
    def test_package_exports_initial_version(self) -> None:
        module_spec = find_spec("bitbucket_helper")
        self.assertIsNotNone(module_spec, "src/bitbucket_helper must be importable")

        package = import_module("bitbucket_helper")
        self.assertEqual(package.__version__, "0.1.0")


if __name__ == "__main__":
    unittest.main()
