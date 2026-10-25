import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('interactive_defaults', Path(__file__).with_name('assistant-interactive-defaults.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DefaultsTest(unittest.TestCase):
    def test_preserves_unrelated_aliases_models_defaults_and_memory(self):
        before = {'other': {'qwen3.8-flash': {'enable_thinking': True}},
                  'keel-assistant-staging': {'qwen3.8-flash': {'temperature': .2, 'memory': {'enable_thinking': False}},
                                             'future-model': {'thinking': {'type': 'enabled'}}}}
        original = copy.deepcopy(before)
        after = module.desired(before)
        self.assertEqual(before, original)
        self.assertEqual(after['other'], before['other'])
        self.assertEqual(after['keel-assistant-staging']['future-model'], before['keel-assistant-staging']['future-model'])
        qwen = after['keel-assistant-staging']['qwen3.8-flash']
        self.assertEqual(qwen['temperature'], .2)
        self.assertEqual(qwen['memory'], {'enable_thinking': False})
        self.assertEqual(qwen['interactive'], {'enable_thinking': False})
        self.assertEqual(module.desired(after), after)


if __name__ == '__main__':
    unittest.main()
