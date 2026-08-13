// 读路径不应被规划等待拖死。用法：k6 run -e BASE=http://localhost:8080 -e TOKEN=... scripts/k6/pool.js
import http from 'k6/http'
import { check } from 'k6'

export const options = { vus: 30, duration: '15s' }

export default function () {
  const base = __ENV.BASE || 'http://localhost:8080'
  const res = http.get(`${base}/api/v1/runs`, {
    headers: { Authorization: `Bearer ${__ENV.TOKEN || ''}` },
  })
  check(res, { 'reads stay fast': (r) => r.status === 200 && r.timings.duration < 1000 })
}
