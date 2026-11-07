// Source files fetched once per kernel and path.

import { api } from "./api";

const cache = new Map<string, Promise<string[]>>();

export function sourceLines(kernel: string, path: string): Promise<string[]> {
  const key = `${kernel}:${path}`;
  let p = cache.get(key);
  if (!p) {
    p = api.source(kernel, path).then((f) => f.lines);
    p.catch(() => cache.delete(key));
    cache.set(key, p);
  }
  return p;
}
