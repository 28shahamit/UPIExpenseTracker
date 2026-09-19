package com.expensetracker.upi;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/** Renders a filtered list of {@link Expense} rows as classified cards (category icon + color, status chip). Tapping a row invokes the supplied listener (HistoryActivity opens the edit/delete dialog). */
final class ExpenseAdapter extends RecyclerView.Adapter<ExpenseAdapter.ViewHolder> {

    interface OnExpenseClickListener {
        void onExpenseClick(Expense expense);
    }

    private final List<Expense> items;
    private final OnExpenseClickListener listener;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    ExpenseAdapter(List<Expense> items, OnExpenseClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_expense, parent, false);
        return new ViewHolder(v);
    }

    @Override public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        Expense e = items.get(position);
        android.content.Context ctx = h.itemView.getContext();
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onExpenseClick(e); });

        h.tvCategoryEmoji.setText(CategoryMeta.emojiFor(e.category));
        int catColor = ctx.getColor(CategoryMeta.colorResFor(e.category));
        h.vCategoryIconBg.setBackgroundTintList(ColorStateList.valueOf(withAlpha(catColor, 40)));
        h.tvCategoryEmoji.setTextColor(catColor);

        h.tvMerchantName.setText(e.merchant);
        String noteLine = e.category + (TextUtils.isEmpty(e.note) ? "" : "  •  " + e.note);
        h.tvCategoryNote.setText(noteLine);
        h.tvDateTime.setText(dateFmt.format(new java.util.Date(e.createdAt)));
        h.tvAmount.setText("\u20B9" + String.format(Locale.US, "%.2f", e.amount));

        int chipColor, chipBg;
        String chipText;
        if (e.isConfirmed()) {
            chipColor = ctx.getColor(R.color.status_success);
            chipBg = ctx.getColor(R.color.status_success_bg);
            chipText = "SUCCESS".equals(e.status) ? "PAID" : "LOGGED";
        } else if (e.isFailed()) {
            chipColor = ctx.getColor(R.color.status_failed);
            chipBg = ctx.getColor(R.color.status_failed_bg);
            chipText = "CANCELLED".equals(e.status) ? "CANCELLED" : "FAILED";
        } else {
            chipColor = ctx.getColor(R.color.status_pending);
            chipBg = ctx.getColor(R.color.status_pending_bg);
            chipText = "PENDING";
        }
        h.tvStatusChip.setText(chipText);
        h.tvStatusChip.setTextColor(chipColor);
        h.tvStatusChip.setBackgroundTintList(ColorStateList.valueOf(chipBg));
    }

    @Override public int getItemCount() {
        return items.size();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final View vCategoryIconBg;
        final TextView tvCategoryEmoji, tvMerchantName, tvCategoryNote, tvDateTime, tvAmount, tvStatusChip;

        ViewHolder(@NonNull View v) {
            super(v);
            vCategoryIconBg = v.findViewById(R.id.vCategoryIconBg);
            tvCategoryEmoji = v.findViewById(R.id.tvCategoryEmoji);
            tvMerchantName = v.findViewById(R.id.tvMerchantName);
            tvCategoryNote = v.findViewById(R.id.tvCategoryNote);
            tvDateTime = v.findViewById(R.id.tvDateTime);
            tvAmount = v.findViewById(R.id.tvAmount);
            tvStatusChip = v.findViewById(R.id.tvStatusChip);
        }
    }
}
