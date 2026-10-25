import React, { useEffect, useRef } from 'react';

export function ChatTools({ open, title, onClose, children }: {
  open: boolean; title: string; onClose: () => void; children: React.ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const content = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const current = dialog.current;
    if (open && current && !current.open) {
      current.showModal();
      if (content.current) content.current.scrollTop = 0;
    }
    else if (!open && current?.open) current.close();
  }, [open]);
  return <dialog className="tools-dialog" ref={dialog} onCancel={onClose}>
    <div className="tools-heading"><h2>{title}</h2><button type="button" onClick={onClose} aria-label={`Close ${title.toLowerCase()}`}>Done</button></div>
    <div className="tools-content" ref={content}>{children}</div>
  </dialog>;
}
