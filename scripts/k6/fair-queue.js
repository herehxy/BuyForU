// 公平队列冒烟：多个用户同时 POST /api/v1/runs。
// 用法：k6 run -e BASE=http://localhost:8080 -e TOKEN=... scripts/k6/fair-queue.js
import http from 'k6/http'
import { check, sleep } from 'k6'

export const options = { vus: 10, duration: '20s' }

export default function () {
  const base = __ENV.BASE || 'http://localhost:8080'
  const res = http.post(`${base}/api/v1/runs`, JSON.stringify({
    conversationId: `c-${__VU}-${__ITER}`,
    message: '5000 元以内的笔记本',
    addressId: __ENV.ADDRESS_ID || 'addr-1',
  }), {
    headers: {
      Authorization: `Bearer ${__ENV.TOKEN || ''}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-${__VU}-${__ITER}`,
    },
  })
  check(res, { 'accepted or limited': (r) => r.status === 202 || r.status === 429 })
  sleep(0.5)
}
