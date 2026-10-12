"use client";

import type { PayMethod, PayMethodId } from "@/lib/payment";

export function PaymentMethodSelect({
  methods,
  value,
  onChange,
  disabled,
  name = "payment-method",
}: {
  methods: PayMethod[];
  value: PayMethodId;
  onChange: (id: PayMethodId) => void;
  disabled?: boolean;
  name?: string;
}) {
  return (
    <fieldset className="flex flex-wrap gap-x-4 gap-y-1 text-sm" disabled={disabled}>
      <legend className="mb-1 font-medium">결제수단</legend>
      {methods.map((m) => (
        <label key={m.id} className="flex items-center gap-1">
          <input type="radio" name={name} checked={value === m.id} onChange={() => onChange(m.id)} />
          {m.label}
        </label>
      ))}
    </fieldset>
  );
}
