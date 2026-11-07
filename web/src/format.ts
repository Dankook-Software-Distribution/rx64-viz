// Showing values by what they are.

/** "0x00000000001234" -> "0x1234"; bigint-safe. */
export function hex(v: string | bigint): string {
  const b = typeof v === "bigint" ? v : BigInt(v);
  return "0x" + b.toString(16);
}

/** 2627735 -> "2.63M", 15307 -> "15.3k". */
export function count(n: number): string {
  if (n >= 1e9) return (n / 1e9).toFixed(n >= 1e10 ? 1 : 2) + "G";
  if (n >= 1e6) return (n / 1e6).toFixed(n >= 1e7 ? 1 : 2) + "M";
  if (n >= 1e4) return (n / 1e3).toFixed(n >= 1e5 ? 0 : 1) + "k";
  return String(n);
}

/** 2627735 -> "2,627,735" */
export function grouped(n: number): string {
  return n.toLocaleString("en-US");
}

/** Names of the set bits in v, from a table of [bit, name]. */
function bits(v: bigint, table: [number, string][]): string[] {
  return table.filter(([b]) => (v >> BigInt(b)) & 1n).map(([, n]) => n);
}

const flagTables: Record<string, [number, string][]> = {
  eflags: [[0, "CF"], [2, "PF"], [4, "AF"], [6, "ZF"], [7, "SF"], [8, "TF"], [9, "IF"], [10, "DF"], [11, "OF"]],
  cr0: [[0, "PE"], [1, "MP"], [4, "ET"], [5, "NE"], [16, "WP"], [18, "AM"], [29, "NW"], [30, "CD"], [31, "PG"]],
  cr4: [[4, "PSE"], [5, "PAE"], [7, "PGE"], [9, "OSFXSR"], [10, "OSXMMEXCPT"], [16, "FSGSBASE"], [20, "SMEP"], [21, "SMAP"]],
  efer: [[0, "SCE"], [8, "LME"], [10, "LMA"], [11, "NXE"]],
};

export function flags(reg: string, v: string): string[] {
  const t = flagTables[reg];
  return t ? bits(BigInt(v), t) : [];
}

/** Selector -> "index 2, ring 3" style text. */
export function selector(v: string): string {
  const s = Number(BigInt(v) & 0xffffn);
  return `GDT[${s >> 3}] RPL ${s & 3}`;
}

export function ring(cpl: number): string {
  return cpl === 3 ? "user" : cpl === 0 ? "kernel" : `ring ${cpl}`;
}
