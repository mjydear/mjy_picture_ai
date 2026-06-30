// =============================================================
// 压测脚本：图片分页列表（项目已内置的缓存版）  POST /api/picture/list/page/vo/cache
// 作为「无缓存 vs 现有缓存」对照基线；P1 多级缓存改造后再压同一脚本做前后对比
// 用法：
//   k6 run perf/baseline/list_vo_cache.js
//   k6 run -e VUS=200 -e DURATION=60s perf/baseline/list_vo_cache.js
// =============================================================
import http from 'k6/http'
import { check } from 'k6'

const BASE = __ENV.BASE_URL || 'http://localhost:8123/api'

export const options = {
  scenarios: {
    list_vo_cache: {
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
  const current = Math.floor(Math.random() * 1000) + 1
  const payload = JSON.stringify({ current: current, pageSize: 12 })
  const params = { headers: { 'Content-Type': 'application/json' } }
  const res = http.post(`${BASE}/picture/list/page/vo/cache`, payload, params)
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
