// =============================================================
// 压测脚本：热门图库排行榜  GET /api/picture/rank/hot
// P1.3 Redis ZSet 榜单场景 —— 高并发读纯走缓存 + 批量取详情
// 用法：
//   k6 run perf/baseline/hot_rank.js
//   k6 run -e VUS=200 -e DURATION=60s -e BASE_URL=http://localhost:8123/api perf/baseline/hot_rank.js
// =============================================================
import http from 'k6/http'
import { check } from 'k6'

const BASE = __ENV.BASE_URL || 'http://localhost:8123/api'

export const options = {
  scenarios: {
    hot_rank: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
  },
}

export default function () {
  const res = http.get(`${BASE}/picture/rank/hot?topN=20`)
  check(res, {
    'status 200': (r) => r.status === 200,
    'code 0': (r) => {
      try {
        return r.json('code') === 0
      } catch (e) {
        return false
      }
    },
    'has records': (r) => {
      try {
        return r.json('data').length > 0
      } catch (e) {
        return false
      }
    },
  })
}