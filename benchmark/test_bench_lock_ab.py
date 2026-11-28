import time
import unittest
from urllib.parse import parse_qs

import bench_lock_ab
from bench_lock_ab import approval_ok, batch, list_reservations, summarize


class LockBenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.winner = {"http": 200, "state": "APPROVED", "id": 1, "code": None}
        self.conflict = {"http": 409, "state": None, "id": None, "code": "RESERVATION_CONFLICT"}
        self.persisted = [{"id": 1, "status": "APPROVED"}, {"id": 2, "status": "PENDING"}]

    def test_approval_requires_one_winner_and_matching_persisted_state(self):
        prepared = [1, 2]
        self.assertTrue(approval_ok([self.winner, self.conflict], self.persisted, prepared))
        self.assertFalse(approval_ok([self.winner, self.winner], self.persisted, prepared))
        self.assertFalse(approval_ok([self.winner, self.conflict], [
            {"id": 1, "status": "APPROVED"}, {"id": 2, "status": "APPROVED"}], prepared))
        self.assertFalse(approval_ok([self.winner, self.conflict], [
            {"id": 1, "status": "PENDING"}, {"id": 2, "status": "APPROVED"}], prepared))

    def test_unrelated_409_is_not_a_valid_approval_conflict(self):
        self.assertFalse(approval_ok([self.winner, dict(self.conflict, code="RESERVATION_LOCK_TIMEOUT")],
                                     self.persisted, [1, 2]))

    def test_approval_rejects_persisted_rows_that_are_not_the_prepared_set(self):
        prepared = [1, 2]
        self.assertFalse(approval_ok(
            [self.winner, self.conflict],
            [{"id": 1, "status": "APPROVED"}, {"id": 99, "status": "PENDING"}],
            prepared))
        self.assertFalse(approval_ok(
            [self.winner, self.conflict],
            [{"id": 1, "status": "APPROVED"}, {"id": 1, "status": "PENDING"}],
            prepared))

    def test_throughput_separates_all_responses_and_successful_operations(self):
        rows = [dict(self.winner, latency_ms=10),
                dict(self.conflict, latency_ms=20)]
        result = summarize(rows, 2, 200)
        self.assertEqual(result["all_responses_per_second"], 1)
        self.assertEqual(result["successful_operations_per_second"], 0.5)
        self.assertEqual(result["latency_success"]["count"], 1)

    def test_batch_returns_all_results(self):
        rows, wall = batch([lambda i=i: i for i in range(3)])
        self.assertEqual(rows, [0, 1, 2])
        self.assertGreater(wall, 0)

    def test_batch_wall_starts_when_the_barrier_releases(self):
        original = bench_lock_ab.ThreadPoolExecutor

        class SlowStart(original):
            def submit(self, fn, *args, **kwargs):
                def wrapped():
                    time.sleep(0.25)
                    return fn(*args, **kwargs)
                return super().submit(wrapped)

        bench_lock_ab.ThreadPoolExecutor = SlowStart
        try:
            rows, wall = batch([lambda: 1, lambda: 2])
        finally:
            bench_lock_ab.ThreadPoolExecutor = original
        self.assertEqual(sorted(rows), [1, 2])
        self.assertLess(wall, 0.2)

    def test_list_reservations_pages_by_id_and_drops_overlap(self):
        calls = []

        def fake_request(api, token, method, path, body=None):
            calls.append(path)
            if "page=0" in path:
                content = [{"id": 1, "equipmentId": 7, "status": "PENDING"},
                           {"id": 2, "equipmentId": 7, "status": "PENDING"}]
            else:
                content = [{"id": 2, "equipmentId": 7, "status": "PENDING"},
                           {"id": 3, "equipmentId": 7, "status": "PENDING"}]
            return 200, {"content": content, "page": 0, "size": 100, "totalElements": 3, "totalPages": 2}, 1.0, ""

        original = bench_lock_ab.request
        bench_lock_ab.request = fake_request
        try:
            rows = list_reservations(None, "token", 7)
        finally:
            bench_lock_ab.request = original
        self.assertEqual([row["id"] for row in rows], [1, 2, 3])
        query = parse_qs(calls[0].split("?", 1)[1])
        self.assertEqual(query["sort"], ["id,asc"])
        self.assertEqual(query["size"], ["100"])
        self.assertEqual(query["equipmentId"], ["7"])


if __name__ == "__main__":
    unittest.main()
