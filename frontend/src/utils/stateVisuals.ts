export type StateVisual = {
  label: string;
  iconName: string;
  borderClass: string;
  textClass: string;
  barClass: string;
  dotClass: string;
  chipClass: string;
  softBgClass: string;
  nodeBgClass: string;
  selectedRingClass: string;
  /** Concrete stroke colour for SVG edges/marks (Tailwind classes can't reach SVG attributes). */
  stroke: string;
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
    nodeBgClass: 'bg-[#1b2028]',
    selectedRingClass: 'ring-[#94a3b8]/45',
    stroke: '#94a3b8',
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
    nodeBgClass: 'bg-[#142326]',
    selectedRingClass: 'ring-status-info/45',
    stroke: '#84d5cd',
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
    nodeBgClass: 'bg-[#261d19]',
    selectedRingClass: 'ring-status-warning/45',
    stroke: '#f2795a',
  },
  Wait: {
    label: 'WAIT',
    iconName: 'schedule',
    borderClass: 'border-[#c4b5fd]',
    textClass: 'text-[#c4b5fd]',
    barClass: 'bg-[#c4b5fd]',
    dotClass: 'bg-[#c4b5fd]',
    chipClass: 'border-[#c4b5fd]/30 bg-[#c4b5fd]/10 text-[#c4b5fd]',
    softBgClass: 'bg-[#c4b5fd]/[0.06]',
    nodeBgClass: 'bg-[#211d2c]',
    selectedRingClass: 'ring-[#c4b5fd]/45',
    stroke: '#c4b5fd',
  },
  Succeed: {
    label: 'SUCCEED',
    iconName: 'check_circle',
    borderClass: 'border-[#4ade80]',
    textClass: 'text-[#4ade80]',
    barClass: 'bg-[#4ade80]',
    dotClass: 'bg-[#4ade80]',
    chipClass: 'border-[#4ade80]/30 bg-[#4ade80]/10 text-[#4ade80]',
    softBgClass: 'bg-[#4ade80]/[0.06]',
    nodeBgClass: 'bg-[#17271d]',
    selectedRingClass: 'ring-[#4ade80]/45',
    stroke: '#4ade80',
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
    nodeBgClass: 'bg-[#2b191c]',
    selectedRingClass: 'ring-status-error/45',
    stroke: '#ffb4ab',
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
    nodeBgClass: 'bg-[#14262b]',
    selectedRingClass: 'ring-[#06b6d4]/45',
    stroke: '#22d3ee',
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
    nodeBgClass: 'bg-[#291824]',
    selectedRingClass: 'ring-[#ec4899]/45',
    stroke: '#ec4899',
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
