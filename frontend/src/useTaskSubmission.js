import { useEffect, useRef, useState } from "react";
import { pollTaskSubmission } from "./api";

export function useTaskSubmission() {
  const request = useRef(null);
  const [progress, setProgress] = useState(null);
  useEffect(() => () => request.current?.abort(), []);

  const submit = async (command) => {
    if (request.current) return null;
    const controller = new AbortController();
    request.current = controller;
    const startedAt = new Date().toISOString();
    setProgress({ state: "requesting", checks: [], startedAt });
    try {
      return await pollTaskSubmission(command, (snapshot) => {
        if (!controller.signal.aborted) setProgress({ ...snapshot, startedAt });
      }, controller.signal);
    } catch (error) {
      if (controller.signal.aborted) return null;
      setProgress((previous) => ({ ...previous, state: "failed", current: "" }));
      throw error;
    } finally {
      if (request.current === controller) request.current = null;
    }
  };

  const reset = () => {
    if (!request.current) setProgress(null);
  };

  return { progress, submit, reset };
}
