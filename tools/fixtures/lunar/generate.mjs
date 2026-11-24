// Generates services/chart-svc/tests/fixtures/lunar/vn-1900-2100.json from date-chinese
// (MIT, independent VSOP87/ELP implementation via astronomia), with the calendar meridian
// fixed at UTC+7 (VN) and UTC+8 (CN) to match chart-svc's definition.
// Run via Docker only, see README.md next to the fixture.
import { CalendarChinese } from 'date-chinese'

class FixedOffset extends CalendarChinese {
  constructor(hours) {
    super()
    this.hours = hours
  }
  timeshiftUTC() {
    return this.hours / 24
  }
}

function months(hours) {
  const cal = new FixedOffset(hours)
  const out = []
  const d = new Date(Date.UTC(1900, 0, 1))
  const end = Date.UTC(2100, 11, 31)
  while (d.getTime() <= end) {
    const y = d.getUTCFullYear()
    const m = d.getUTCMonth() + 1
    const day = d.getUTCDate()
    cal.fromGregorian(y, m, day)
    if (cal.day === 1) {
      const iso = `${y}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`
      out.push([iso, cal.yearFromEpochCycle(), cal.month, Boolean(cal.leap)])
    }
    d.setUTCDate(day + 1)
  }
  return out
}

const vn = months(7)
const cn = months(8)
const rows = (xs) => xs.map((r) => '    ' + JSON.stringify(r)).join(',\n')
process.stdout.write(
  '{\n' +
    '  "source": "date-chinese@2.2.1 (MIT), meridian fixed at UTC+7 (vn) / UTC+8 (cn)",\n' +
    '  "format": ["first solar day of lunar month", "lunar year", "lunar month", "leap"],\n' +
    `  "vn": [\n${rows(vn)}\n  ],\n` +
    `  "cn": [\n${rows(cn)}\n  ]\n}\n`
)
