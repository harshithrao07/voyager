// Client-side check for the backend's Spring CronExpression format
// (6 fields: second minute hour day month weekday, plus @macros).
// Shallow by design — the backend remains the source of truth.

const CRON_MACROS = new Set(['@yearly', '@annually', '@monthly', '@weekly', '@daily', '@midnight', '@hourly']);

const CRON_FIELDS: { name: string; min: number; max: number; names?: string[] }[] = [
  { name: 'second', min: 0, max: 59 },
  { name: 'minute', min: 0, max: 59 },
  { name: 'hour', min: 0, max: 23 },
  { name: 'day-of-month', min: 1, max: 31 },
  { name: 'month', min: 1, max: 12, names: ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'] },
  { name: 'day-of-week', min: 0, max: 7, names: ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] },
];

function cronTokenToNumber(token: string, spec: (typeof CRON_FIELDS)[number]): number | null {
  if (/^\d+$/.test(token)) return Number(token);
  if (spec.names) {
    const index = spec.names.indexOf(token.toUpperCase());
    if (index >= 0) return spec.min === 1 ? index + 1 : index;
  }
  return null;
}

function validateCronField(field: string, spec: (typeof CRON_FIELDS)[number]): string | null {
  if (field === '*' || field === '?') return null;
  for (const part of field.split(',')) {
    if (!part) return `Empty value in the ${spec.name} field.`;
    const [base, step] = part.split('/');
    if (part.includes('/') && (!/^\d+$/.test(step ?? '') || Number(step) < 1)) {
      return `Invalid step "/${step}" in the ${spec.name} field.`;
    }
    if (base === '*' || base === '?') continue;
    // Accept advanced day tokens (L, W, #) without deep-checking them.
    if (/[LW#]/i.test(base)) {
      if (spec.name === 'day-of-month' || spec.name === 'day-of-week') continue;
      return `Unexpected character in the ${spec.name} field: "${part}".`;
    }
    const bounds = base.split('-');
    if (bounds.length > 2) return `Invalid range in the ${spec.name} field: "${part}".`;
    for (const token of bounds) {
      const value = cronTokenToNumber(token, spec);
      if (value === null) return `Invalid ${spec.name} value: "${token}".`;
      if (value < spec.min || value > spec.max) {
        return `The ${spec.name} value "${token}" is out of range (${spec.min}–${spec.max}).`;
      }
    }
  }
  return null;
}

/**
 * Returns a human-readable error for an invalid cron expression, or null when
 * it is valid. An empty expression is valid (manual-only trigger).
 */
export function validateCron(expression: string): string | null {
  const expr = expression.trim();
  if (!expr) return null; // empty = manual trigger
  if (expr.startsWith('@')) {
    return CRON_MACROS.has(expr.toLowerCase())
      ? null
      : `Unknown macro "${expr}". Try @hourly, @daily, @weekly, @monthly, or @yearly.`;
  }
  const fields = expr.split(/\s+/);
  if (fields.length !== 6) {
    return `Expected 6 fields (second minute hour day month weekday) — got ${fields.length}.`;
  }
  for (let index = 0; index < CRON_FIELDS.length; index += 1) {
    const error = validateCronField(fields[index], CRON_FIELDS[index]);
    if (error) return error;
  }
  return null;
}
