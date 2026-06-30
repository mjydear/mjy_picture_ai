// =============================================================
// 压测脚本：图片详情（无缓存）  GET /api/picture/get/vo?id=
// 高并发点查场景
// 用法：
//   k6 run perf/baseline/detail_vo.js
//   k6 run -e VUS=200 -e DURATION=60s perf/baseline/detail_vo.js
// =============================================================
import http from 'k6/http'
import { check } from 'k6'

const BASE = __ENV.BASE_URL || 'http://localhost:8123/api'
const MAX_ID = Number(__ENV.MAX_ID || 500000)

export const options = {
  scenarios: {
    detail_vo: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
  },
}

export default function () {
  const id = Math.floor(Math.random() * MAX_ID) + 1
  const res = http.get(`${BASE}/picture/get/vo?id=${id}`)
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
