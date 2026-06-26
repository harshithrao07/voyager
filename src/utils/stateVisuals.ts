export type StateVisual = {
  label: string;
  iconName: string;
  borderClass: string;
  textClass: string;
  barClass: string;
  dotClass: string;
  chipClass: string;
  softBgClass: string;
  selectedRingClass: string;
};

const stateVisualMap: Record<string, StateVisual> = {
  Pass: {
    label: 'PASS',
    iconName: 'swap_horiz',
    borderClass: 'border-[#94a3b8]',
    textClass: 'text-[#94a3b8]',
    barClass: 'bg-[#94a3b8]',
    dotClass: 'bg-[#94a3b8]',
    chipClass: 'border-[#94a3b8]/30 bg-[#94a3b8]/10 text-[#cbd5e1]',
    softBgClass: 'bg-[#94a3b8]/[0.06]',
    selectedRingClass: 'ring-[#94a3b8]/45',
  },
  Task: {
    label: 'TASK',
    iconName: 'database',
    borderClass: 'border-status-info',
    textClass: 'text-status-info',
    barClass: 'bg-status-info',
    dotClass: 'bg-status-info',
    chipClass: 'border-status-info/30 bg-status-info/10 text-status-info',
    softBgClass: 'bg-status-info/[0.06]',
    selectedRingClass: 'ring-status-info/45',
  },
  Choice: {
    label: 'CHOICE',
    iconName: 'call_split',
    borderClass: 'border-status-warning',
    textClass: 'text-status-warning',
    barClass: 'bg-status-warning',
    dotClass: 'bg-status-warning',
    chipClass: 'border-status-warning/30 bg-status-warning/10 text-status-warning',
    softBgClass: 'bg-status-warning/[0.07]',
    selectedRingClass: 'ring-status-warning/45',
  },
  Wait: {
    label: 'WAIT',
    iconName: 'schedule',
    borderClass: 'border-status-accent',
    textClass: 'text-status-accent',
    barClass: 'bg-status-accent',
    dotClass: 'bg-status-accent',
    chipClass: 'border-status-accent/30 bg-status-accent/10 text-status-accent',
    softBgClass: 'bg-status-accent/[0.06]',
    selectedRingClass: 'ring-status-accent/45',
  },
  Succeed: {
    label: 'SUCCEED',
    iconName: 'check_circle',
    borderClass: 'border-status-success',
    textClass: 'text-status-success',
    barClass: 'bg-status-success',
    dotClass: 'bg-status-success',
    chipClass: 'border-status-success/30 bg-status-success/10 text-status-success',
    softBgClass: 'bg-status-success/[0.06]',
    selectedRingClass: 'ring-status-success/45',
  },
  Fail: {
    label: 'FAIL',
    iconName: 'cancel',
    borderClass: 'border-status-error',
    textClass: 'text-status-error',
    barClass: 'bg-status-error',
    dotClass: 'bg-status-error',
    chipClass: 'border-status-error/35 bg-status-error/10 text-status-error',
    softBgClass: 'bg-status-error/[0.08]',
    selectedRingClass: 'ring-status-error/45',
  },
  Parallel: {
    label: 'PARALLEL',
    iconName: 'splitscreen',
    borderClass: 'border-[#06b6d4]',
    textClass: 'text-[#22d3ee]',
    barClass: 'bg-[#06b6d4]',
    dotClass: 'bg-[#06b6d4]',
    chipClass: 'border-[#06b6d4]/35 bg-[#06b6d4]/10 text-[#67e8f9]',
    softBgClass: 'bg-[#06b6d4]/[0.06]',
    selectedRingClass: 'ring-[#06b6d4]/45',
  },
  Map: {
    label: 'MAP',
    iconName: 'layers',
    borderClass: 'border-[#ec4899]',
    textClass: 'text-[#f472b6]',
    barClass: 'bg-[#ec4899]',
    dotClass: 'bg-[#ec4899]',
    chipClass: 'border-[#ec4899]/35 bg-[#ec4899]/10 text-[#f9a8d4]',
    softBgClass: 'bg-[#ec4899]/[0.06]',
    selectedRingClass: 'ring-[#ec4899]/45',
  },
};

function normalizeStateType(type?: string) {
  if (!type) return 'Task';
  const normalized = type.toLowerCase();
  return Object.keys(stateVisualMap).find((key) => key.toLowerCase() === normalized) || 'Task';
}

export function getStateVisual(type?: string) {
  return stateVisualMap[normalizeStateType(type)];
}
