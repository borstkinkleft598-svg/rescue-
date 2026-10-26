import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("comparison", Path(__file__).parents[1] / "compare_overnight.py")
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


class ComparisonTest(unittest.TestCase):
    def fixture(self, root, status="success", score="12", health=True, name="berlin"):
        if health is not None:
            (root / "health.json").write_text(json.dumps({"valid": health}))
        with (root / "summary.csv").open("w") as file:
            writer = csv.DictWriter(file, fieldnames=["map", "status", "score", "log_dir"])
            writer.writeheader()
            writer.writerow(dict(map=name, status=status, score=score, log_dir=str(root)))

    def test_partial_failed_scores_are_never_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            self.fixture(p, status="failed", score="99")
            self.assertIsNone(comparison.scores(p, True)["berlin"])

    def test_current_requires_health_but_legacy_audited_baseline_may_lack_it(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            self.fixture(p, health=None)
            self.assertIsNone(comparison.scores(p, True)["berlin"])
            self.assertEqual(12, comparison.scores(p, False)["berlin"])

    def test_invalid_health_and_nonfinite_score_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            self.fixture(p, health=False)
            self.assertIsNone(comparison.scores(p, True)["berlin"])
            self.fixture(p, score="nan", health=True)
            self.assertIsNone(comparison.scores(p, True)["berlin"])

    def test_ny_never_reintroduces_audited_invalid_scores(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            self.fixture(p, name="ny")
            self.assertEqual({}, comparison.scores(p, False))

    def test_render_counts_only_pairs_with_valid_scores(self):
        result = comparison.render({"a": 12, "b": None, "c": 8, "d": 5},
                                   {"a": 10, "b": 99, "c": 10, "d": 5}, {})
        self.assertIn("3张：1升、1降、1持平", result)
        self.assertIn("+20.00%", result)
        self.assertIn("−20.00%".replace("−", "-"), result)

    def test_beating_v3_does_not_mean_beating_the_previous_best(self):
        result = comparison.render({"a":12}, {"a":10}, {"a":13}, {"a":15})
        self.assertIn("相对此前最好", result)
        self.assertIn("-20.00%", result)
        self.assertIn("各图最好有效成绩相比：0升、1降、0持平", result)


if __name__ == "__main__":
    unittest.main()
