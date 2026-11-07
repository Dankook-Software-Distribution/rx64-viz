// A value that can be watched: subscribers get it now and on every change.

export interface Store<T> {
  readonly value: T;
  set(v: T): void;
  subscribe(fn: (v: T) => void): () => void;
}

export function store<T>(initial: T): Store<T> {
  let value = initial;
  const listeners = new Set<(v: T) => void>();
  return {
    get value() {
      return value;
    },
    set(v: T) {
      value = v;
      for (const fn of listeners) fn(v);
    },
    subscribe(fn) {
      listeners.add(fn);
      fn(value);
      return () => listeners.delete(fn);
    },
  };
}
