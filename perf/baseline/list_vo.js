// =============================================================
// 压测脚本：图片分页列表（无缓存）  POST /api/picture/list/page/vo
// 主线高并发场景 —— 公共图库浏览
// 用法：
//   k6 run perf/baseline/list_vo.js
//   k6 run -e VUS=200 -e DURATION=60s -e BASE_URL=http://localhost:8123/api perf/baseline/list_vo.js
// 关注指标：http_reqs(rate≈QPS)、http_req_duration p(95)/p(99)、http_req_failed
// =============================================================
import http from 'k6/http'
import { check } from 'k6'

const BASE = __ENV.BASE_URL || 'http://localhost:8123/api'

export const options = {
  scenarios: {
    list_vo: {
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
  // current 随机 1~1000，模拟正常浏览 + 偶尔深翻页，暴露 limit offset 退化
  const current = Math.floor(Math.random() * 1000) + 1
  const payload = JSON.stringify({ current: current, pageSize: 12 })
  const params = { headers: { 'Content-Type': 'application/json' } }
  const res = http.post(`${BASE}/picture/list/page/vo`, payload, params)
  check(res, {
    'status 200': (r) => r.status === 200,
    'code 0': (r) => {
      try {
        return r.json('code') === 0
      } catch (e) {
        return false
      }
    },
  })
}
