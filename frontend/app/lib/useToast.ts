import { useCallback, useEffect, useRef, useState } from "react";
import type { ToastState } from "./types";

const TOAST_DURATION_MS = 3200;

export function useToast() {
  const [toast, setToast] = useState<ToastState>(null);
  const timer = useRef<number | null>(null);

  const clearToast = useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
    setToast(null);
  }, []);

  const notify = useCallback((message: string, error = false) => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    setToast({ message, error });
    timer.current = window.setTimeout(() => {
      timer.current = null;
      setToast(null);
    }, TOAST_DURATION_MS);
  }, []);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
  }, []);

  return { toast, notify, clearToast };
}
