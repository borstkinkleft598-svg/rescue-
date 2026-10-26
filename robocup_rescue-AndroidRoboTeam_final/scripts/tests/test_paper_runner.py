import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("paper_runner", Path(__file__).parents[1] / "run_paper_experiment.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class RunnerTest(unittest.TestCase):
    def fixture(self, root):
        agent, server = root / "agent", root / "server"
        (agent / "config").mkdir(parents=True)
        for name in ("launch.cfg", "paper-module.cfg"):
            (agent / "config" / name).write_text("fixture")
        (agent / "config/paper-develop.json").write_text('{"Paper.enabled":true}')
        (agent / "src/main/resources").mkdir(parents=True)
        (agent / "src/main/resources/log4j.properties").write_text("log4j.rootLogger=info")
        (server / "scripts").mkdir(parents=True)
        for name in ("functions.sh", "start-precompute.sh", "start-comprun.sh"):
            (server / "scripts" / name).write_text("fixture")
        (server / "log4j.properties").write_text("log4j.rootLogger=trace, console\nlog4j.category.kernel=debug, kernel\n")
        scenario = server / "maps/test"
        (scenario / "map").mkdir(parents=True)
        (scenario / "map/map.gml").write_text("fixture")
        (scenario / "config").mkdir()
        (scenario / "config/common.cfg").write_text("kernel.port: 27931\nrandom.seed: 1\n")
        (scenario / "config/kernel.cfg").write_text("!include common.cfg\nkernel.timesteps: 300\n")
        return agent, server

    def test_profiles_preserve_original_map_and_competition_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            agent, server = self.fixture(root)
            original = (server / "maps/test/config/kernel.cfg").read_text()
            run = runner.prepare(agent,server,"test","ldcop",42,12,root/"output",28031)
            self.assertEqual(original,(server / "maps/test/config/kernel.cfg").read_text())
            kernel = (run / "server/maps/test/config/kernel.cfg").read_text()
            self.assertIn("kernel.timesteps: 12",kernel)
            self.assertIn("PaperFullCityPerception",kernel)
            self.assertIn("perception.standard.hp-precision: 1",kernel)
            self.assertIn("kernel.port: 28031",(run / "server/maps/test/config/common.cfg").read_text())
            self.assertFalse(json.loads((run/"agent-fs/config/develop.json").read_text())["Paper.dcopBaseline"])

    def test_baseline_uses_identical_server_scenario_and_seed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            agent, server = self.fixture(root)
            runs = [runner.prepare(agent,server,"test",mode,42,None,root/"output",28031) for mode in ("ldcop","dcop")]
            for name in ("kernel.cfg","common.cfg"):
                self.assertEqual((runs[0]/"server/maps/test/config"/name).read_text(),
                                 (runs[1]/"server/maps/test/config"/name).read_text())
            self.assertTrue(json.loads((runs[1]/"agent-fs/config/develop.json").read_text())["Paper.dcopBaseline"])

    def test_inconsistent_metrics_are_not_silently_treated_as_a_valid_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = Path(tmp)
            (run/"metrics").mkdir()
            for actor, rescued in ((1,2),(2,3)):
                (run/"metrics"/f"agent-{actor}.json").write_text(json.dumps({"actor":actor,"time":10,
                    "cohort":4,"rescued":rescued,"virtualTransported":1,"unknownArrivals":0,"targetSwitches":2}))
            summary = runner.summarize(run,"complete")
            self.assertFalse(summary["metrics_consistent"])
            self.assertIsNone(summary["official_kernel_score"])

    def test_invalid_map_name_cannot_escape_the_maps_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            agent, server = self.fixture(root)
            with self.assertRaises(ValueError):
                runner.prepare(agent,server,"../test","ldcop",1,None,root/"output",28031)

    def test_runtime_settings_are_applied_and_recorded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            agent, server = self.fixture(root)
            configured = dict(agent_heap_mb=6144, think_time_ms=120000,
                              startup_time_ms=300000, precompute_timeout_seconds=300)
            (agent/"config/paper-runtime.json").write_text(json.dumps(configured))
            run = runner.prepare(agent,server,"test","ldcop",1,None,root/"output",28031)
            self.assertEqual(configured, json.loads((run/"runtime-settings.json").read_text()))
            self.assertEqual(configured, json.loads((run/"manifest.json").read_text())["runtime_settings"])
            kernel = (run/"server/maps/test/config/kernel.cfg").read_text()
            self.assertIn("kernel.agents.think-time: 120000", kernel)
            self.assertIn("kernel.startup.connect-time: 300000", kernel)
            self.assertIn("kernel.timesteps: 300", kernel)

    def test_invalid_runtime_settings_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            agent, _ = self.fixture(Path(tmp))
            for settings in ({"agent_heap_mb": 99999}, {"think_time_ms": 0},
                             {"agent_heap_mb": True}, {"unknown": 1}):
                (agent/"config/paper-runtime.json").write_text(json.dumps(settings))
                with self.assertRaises(ValueError):
                    runner.runtime_settings(agent)


if __name__ == "__main__":
    unittest.main()
