import unittest

from bench_lock_ab import approval_ok, batch, summarize


class LockBenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.winner = {"http": 200, "state": "APPROVED", "id": 1, "code": None}
        self.conflict = {"http": 409, "state": None, "id": None, "code": "RESERVATION_CONFLICT"}
        self.persisted = [{"id": 1, "status": "APPROVED"}, {"id": 2, "status": "PENDING"}]

    def test_approval_requires_one_winner_and_matching_persisted_state(self):
        self.assertTrue(approval_ok([self.winner, self.conflict], self.persisted))
        self.assertFalse(approval_ok([self.winner, self.winner], self.persisted))
        self.assertFalse(approval_ok([self.winner, self.conflict], [
            {"id": 1, "status": "APPROVED"}, {"id": 2, "status": "APPROVED"}]))
        self.assertFalse(approval_ok([self.winner, self.conflict], [
            {"id": 1, "status": "PENDING"}, {"id": 2, "status": "APPROVED"}]))

    def test_unrelated_409_is_not_a_valid_approval_conflict(self):
        self.assertFalse(approval_ok([self.winner, dict(self.conflict, code="RESERVATION_LOCK_TIMEOUT")],
                                     self.persisted))

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


if __name__ == "__main__":
    unittest.main()
