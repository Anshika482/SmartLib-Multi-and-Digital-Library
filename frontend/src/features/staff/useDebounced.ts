import { useEffect, useState } from 'react';

export function useDebounced(initial: string, delay = 300) {
  const [value, setValue] = useState(initial);
  const [debounced, setDebounced] = useState(initial);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value.trim()), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return [value, setValue, debounced] as const;
}
