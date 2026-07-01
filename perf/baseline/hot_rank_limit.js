// =============================================================
// 压测脚本：热门榜限流验证 GET /api/picture/rank/hot
// P1.4 Redis Lua 令牌桶 —— 超阈值返回业务码 42900，服务不雪崩
// 用法：
//   k6 run perf/baseline/hot_rank_limit.js
// =============================================================
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE_URL || 'http://localhost:8123/api'
const limited = new Counter('business_rate_limited')

export const options = {
  scenarios: {
    hot_rank_limit: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    business_rate_limited: ['count>0'],
  },
}

export default function () {
  const res = http.get(`${BASE}/picture/rank/hot?topN=20`)
  let code = -1
  try {
    code = res.json('code')
  } catch (e) {
    code = -1
  }
  if (code === 42900) {
    limited.add(1)
  }
  check(res, {
    'status 200': (r) => r.status === 200,
    'code 0 or 42900': () => code === 0 || code === 42900,
  })
}