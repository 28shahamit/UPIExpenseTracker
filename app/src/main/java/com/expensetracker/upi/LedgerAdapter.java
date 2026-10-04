package com.expensetracker.upi;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

final class LedgerAdapter extends RecyclerView.Adapter<LedgerAdapter.Holder> {
    private final List<LedgerEntry> entries;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault());

    LedgerAdapter(List<LedgerEntry> entries) {
        this.entries = entries;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ledger_entry, parent, false);
        return new Holder(v);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        LedgerEntry e = entries.get(position);
        h.tvReason.setText(iconFor(e.type) + e.reason);
        h.tvDate.setText(dateFmt.format(new java.util.Date(e.createdAt)));
        h.tvDelta.setText(Money.formatSigned(e.delta));
        h.tvDelta.setTextColor(h.tvDelta.getResources().getColor(
                e.delta >= 0 ? R.color.status_success : R.color.status_failed, null));
        h.tvBalanceAfter.setText("Balance: " + Money.format(e.balanceAfter));
    }

    @Override public int getItemCount() { return entries.size(); }

    /** One glance should tell you what kind of change a row is, without reading the reason
     * text - null/unrecognized types (rows written before the type column existed) just get
     * no icon rather than a guess. 🧾 Expense, 🔧 Manual correction, 💰 Credited, 💸 Debited,
     * ↩️ Refund (reserved - no flow writes this type yet). */
    private static String iconFor(String type) {
        if (type == null) return "";
        switch (type) {
            case "EXPENSE": return "\uD83E\uDDFE "; // 🧾
            case "CORRECTION": return "\uD83D\uDD27 "; // 🔧
            case "CREDIT": return "\uD83D\uDCB0 "; // 💰
            case "DEBIT": return "\uD83D\uDCB8 "; // 💸
            case "REFUND": return "\u21A9\uFE0F "; // ↩️
            default: return "";
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView tvReason, tvDate, tvDelta, tvBalanceAfter;
        Holder(View v) {
            super(v);
            tvReason = v.findViewById(R.id.tvLedgerReason);
            tvDate = v.findViewById(R.id.tvLedgerDate);
            tvDelta = v.findViewById(R.id.tvLedgerDelta);
            tvBalanceAfter = v.findViewById(R.id.tvLedgerBalanceAfter);
        }
    }
}
