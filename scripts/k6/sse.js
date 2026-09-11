// SSE 连接冒烟。用法：k6 run -e BASE=http://localhost:8080 -e TOKEN=... -e RUN_ID=... scripts/k6/sse.js
import http from 'k6/http'
import { check } from 'k6'

export const options = { vus: 20, duration: '15s' }

export default function () {
  const base = __ENV.BASE || 'http://localhost:8080'
  const runId = __ENV.RUN_ID || 'missing-run'
  const res = http.get(`${base}/api/v1/runs/${runId}/events`, {
    headers: {
      Authorization: `Bearer ${__ENV.TOKEN || ''}`,
      Accept: 'text/event-stream',
      'Last-Event-ID': '0',
    },
    timeout: '5s',
  })
  check(res, { 'sse opened or rejected cleanly': (r) => r.status === 200 || r.status === 403 || r.status === 401 })
}
