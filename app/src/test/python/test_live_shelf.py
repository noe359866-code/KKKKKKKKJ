"""Pruebas del constructor de la estantería de TV y agenda (`_liveshelf`).

Sin red: cada proveedor es un doble con los cargadores que el constructor usa de
verdad (`load_index`, `load_channels`, `load_agenda`), y se sustituye
`importlib.import_module` para no importar nada del proyecto.
"""
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
sys.path[:0] = [str(ROOT / "app/src/main/python"), str(ROOT / "server/scrapers")]

import _liveshelf as shelf


def module(**loaders):
    return types.SimpleNamespace(**loaders)


def provider(pid, label=""):
    return {"id": pid, "label": label or pid.title()}


class ShelfTests(unittest.TestCase):
    def build(self, modules, providers, **kwargs):
        with patch.object(shelf.importlib, "import_module", side_effect=lambda name: modules[name]):
            return shelf.build(providers, **kwargs)

    # ------------------------------------------------------------------ canales

    def test_channels_from_every_provider_without_repeats(self):
        modules = {
            "futbolibre": module(load_channels=lambda h, d: [
                {"slug": "espn-1", "name": "ESPN 1"},
                {"slug": "fox-sports", "name": "Fox Sports"},
            ]),
            "ntv": module(load_index=lambda h, d: [{"id": "nfl-network", "name": "NFL Network"}]),
            "streamcorner": module(load_index=lambda h, d: [
                {"id": "nfl/nfl-network", "name": "NFL Network"},
                {"id": "mlb/mlb-network", "name": "MLB Network"},
            ]),
        }
        result = self.build(modules, [provider("futbolibre", "Fútbol Libre"),
                                      provider("ntv", "NTV"),
                                      provider("streamcorner", "StreamCorner")])
        names = [item["name"] for item in result["channels"]]
        self.assertEqual(["ESPN 1", "Fox Sports", "MLB Network", "NFL Network"], names)
        # NFL Network aparece en dos sitios y se publica una sola vez.
        self.assertEqual(1, names.count("NFL Network"))
        first = next(item for item in result["channels"] if item["name"] == "NFL Network")
        self.assertEqual("tv:ntv:nfl-network", first["id"], "manda la prioridad del proveedor")
        self.assertEqual("NTV", first["sourceName"])
        self.assertEqual("En directo", first["releaseInfo"])
        self.assertTrue(first["live"])

    def test_sports_channels_go_first_and_the_rest_is_alphabetical(self):
        modules = {
            "futbolibre": module(load_channels=lambda h, d: [
                {"slug": "fox-sports", "name": "Fox Sports", "league": "Deportes"},
                {"slug": "discovery", "name": "Discovery"},
                {"slug": "espn-1", "name": "ESPN 1", "league": "Deportes"},
            ]),
        }
        result = self.build(modules, [provider("futbolibre")])
        self.assertEqual(["ESPN 1", "Fox Sports", "Discovery"],
                         [item["name"] for item in result["channels"]])

    def test_a_logo_becomes_the_card_poster(self):
        modules = {"ntv": module(load_index=lambda h, d: [
            {"id": "x", "name": "Canal X", "logo": "https://logos.invalid/x.png"}])}
        meta = self.build(modules, [provider("ntv")])["channels"][0]
        self.assertEqual("https://logos.invalid/x.png", meta["poster"])

    # ------------------------------------------------------------------ agenda

    def test_agenda_merges_providers_and_sorts_live_first(self):
        modules = {
            "futbolibre": module(load_agenda=lambda h, d: [
                {"id": "evento:mlb-yankees-vs-red-sox", "title": "MLB: Yankees vs Red Sox",
                 "time": "21:00", "status": "Programado"},
                {"id": "evento:liga-1-cienciano-vs-atletico-grau",
                 "title": "Liga 1: Cienciano vs Atlético Grau", "time": "19:00", "status": "En vivo"},
            ]),
            "damitv": module(load_index=lambda h, d: [
                {"id": "nba-lakers-celtics", "name": "Lakers vs Celtics", "league": "NBA",
                 "status": "En vivo"},
                {"id": "espn-1", "name": "ESPN 1", "league": "Deportes", "status": "En vivo"},
            ]),
        }
        result = self.build(modules, [provider("futbolibre", "Fútbol Libre"), provider("damitv", "DAMITV")])
        events = result["events"]
        self.assertEqual(["Cienciano vs Atlético Grau", "Lakers vs Celtics", "Yankees vs Red Sox"],
                         [item["name"] for item in events])
        live = events[0]
        self.assertEqual("Liga 1", live["league"])
        self.assertEqual(("Cienciano", "Atlético Grau"), (live["home"], live["away"]))
        self.assertEqual("En vivo · 19:00", live["releaseInfo"])
        self.assertTrue(live["onAir"])
        self.assertEqual(["Deportes", "Liga 1"], live["genres"])
        self.assertFalse(events[-1]["onAir"])
        self.assertEqual("Hoy · 21:00", events[-1]["releaseInfo"])
        # Un canal del índice de DAMITV no es un partido: se va a canales.
        self.assertNotIn("ESPN 1", [item["name"] for item in events])
        self.assertIn("ESPN 1", [item["name"] for item in result["channels"]])

    def test_a_sport_category_is_not_a_league(self):
        meta = shelf.event_meta(provider("futbolibre"), {
            "id": "evento:x", "title": "Cienciano vs Atlético Grau",
            "category": "futbol", "time": "19:00", "status": "En vivo"})
        self.assertEqual("", meta["league"])
        self.assertEqual(["Deportes"], meta["genres"])

    def test_broadcast_suffixes_are_dropped_from_team_names(self):
        meta = shelf.event_meta(provider("futbolibre"), {
            "id": "evento:nba", "title": "NBA: Lakers vs Celtics (Final)",
            "time": "02:30", "status": "En vivo"})
        self.assertEqual("Lakers", meta["home"])
        self.assertEqual("Celtics", meta["away"])
        self.assertEqual("Lakers vs Celtics", meta["name"])

    # ------------------------------------------------------------------ curados

    def test_curated_items_are_pinned_first_and_block_duplicates(self):
        modules = {"futbolibre": module(load_channels=lambda h, d: [
            {"slug": "espn-1", "name": "ESPN 1"},
            {"slug": "fox-sports", "name": "Fox Sports"},
        ])}
        curated = [{"id": "tv:futbolibre:espn-1", "name": "ESPN 1", "category": "deportes",
                    "genres": ["Deportes", "Canales"]}]
        result = self.build(modules, [provider("futbolibre", "Fútbol Libre")],
                            curated=curated, sections=("channels",))
        self.assertEqual("tv:futbolibre:espn-1", result["channels"][0]["id"])
        self.assertEqual(1, len([item for item in result["channels"] if item["name"] == "ESPN 1"]))
        self.assertEqual("Fútbol Libre", result["channels"][0]["sourceName"])

    # ------------------------------------------------------------------ robustez

    def test_a_broken_provider_does_not_empty_the_row(self):
        def boom(headers, deadline):
            raise ValueError("parser roto")

        modules = {
            "futbolibre": module(load_channels=boom),
            "ntv": module(load_index=lambda h, d: [{"id": "nfl", "name": "NFL Network"}]),
        }
        result = self.build(modules, [provider("futbolibre"), provider("ntv")])
        self.assertEqual(["NFL Network"], [item["name"] for item in result["channels"]])

    def test_limits_are_respected(self):
        modules = {"ntv": module(load_index=lambda h, d: [
            {"id": f"c{index}", "name": f"Canal {index}"} for index in range(10)])}
        result = self.build(modules, [provider("ntv")], channel_limit=3)
        self.assertEqual(3, len(result["channels"]))
        self.assertEqual(3, result["meta"]["counts"]["channels"])

    def test_sections_can_be_built_one_at_a_time(self):
        calls = []
        modules = {"futbolibre": module(
            load_channels=lambda h, d: calls.append("channels") or [{"slug": "e", "name": "E"}],
            load_agenda=lambda h, d: calls.append("events") or [],
        )}
        self.build(modules, [provider("futbolibre")], sections=("events",))
        self.assertEqual(["events"], calls)

    def test_a_provider_that_is_not_registered_as_live_is_ignored(self):
        result = self.build({}, [provider("gnula")])
        self.assertEqual([], result["channels"])
        self.assertEqual([], result["events"])


if __name__ == "__main__":
    unittest.main()
