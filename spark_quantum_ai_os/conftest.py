"""
SPARK QUANTUM AI OS — pytest conftest
Sets up sys.path so all test files can import project modules without
needing to install the package.
"""
import sys
from pathlib import Path

# Ensure the project root is on sys.path for all tests
ROOT = Path(__file__).parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
