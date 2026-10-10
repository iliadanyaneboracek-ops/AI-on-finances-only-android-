package com.example.aionfinances;

import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Показывает элементы чата в списке RecyclerView. */
public final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<ChatItem> items;

    public ChatAdapter(List<ChatItem> items) {
        this.items = items;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == ChatItem.USER) {
            return new UserHolder(inflater.inflate(R.layout.item_message_user, parent, false));
        }
        if (viewType == ChatItem.CHART) {
            return new ChartHolder(inflater.inflate(R.layout.item_chart, parent, false));
        }
        if (viewType == ChatItem.THINKING) {
            return new ThinkingHolder(inflater.inflate(R.layout.item_thinking, parent, false));
        }
        return new AssistantHolder(inflater.inflate(R.layout.item_message_assistant, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatItem item = items.get(position);
        if (holder instanceof UserHolder) {
            ((UserHolder) holder).bind(item);
        } else if (holder instanceof ChartHolder) {
            ((ChartHolder) holder).bind(item);
        } else if (holder instanceof ThinkingHolder) {
            ((ThinkingHolder) holder).bind(item);
        } else {
            ((AssistantHolder) holder).bind(item);
        }
    }

    static final class UserHolder extends RecyclerView.ViewHolder {
        private final TextView text;
        private final TextView files;

        UserHolder(View view) {
            super(view);
            text = view.findViewById(R.id.text);
            files = view.findViewById(R.id.files);
        }

        void bind(ChatItem item) {
            text.setText(item.text);
            if (item.fileNames.isEmpty()) {
                files.setVisibility(View.GONE);
            } else {
                files.setVisibility(View.VISIBLE);
                files.setText(files.getContext().getString(R.string.attached_files,
                        TextUtils.join(", ", item.fileNames)));
            }
        }
    }

    static final class AssistantHolder extends RecyclerView.ViewHolder {
        private final TextView text;
        private final TextView sources;

        AssistantHolder(View view) {
            super(view);
            text = view.findViewById(R.id.text);
            sources = view.findViewById(R.id.sources);
            sources.setMovementMethod(LinkMovementMethod.getInstance());
        }

        void bind(ChatItem item) {
            text.setText(item.text);
            text.setTextColor(ContextCompat.getColor(text.getContext(),
                    item.type == ChatItem.ERROR ? R.color.error : R.color.text_primary));

            if (item.sources.isEmpty()) {
                sources.setVisibility(View.GONE);
                return;
            }
            // Список источников: каждая строка — ссылка, открывается в браузере по нажатию.
            SpannableStringBuilder sb = new SpannableStringBuilder(
                    sources.getContext().getString(R.string.sources));
            for (int i = 0; i < item.sources.size(); i++) {
                ChatItem.Source source = item.sources.get(i);
                Uri uri = Uri.parse(source.url);
                String host = uri.getHost() == null ? "" : uri.getHost();
                sb.append("\n");
                int start = sb.length();
                sb.append(String.valueOf(i + 1)).append(". ").append(source.title);
                if (!host.isEmpty()) sb.append(" — ").append(host);
                String scheme = uri.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    sb.setSpan(new URLSpan(source.url), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            sources.setText(sb);
            sources.setVisibility(View.VISIBLE);
        }
    }

    /** Размышления модели: свёрнуты до двух строк, нажатие разворачивает и сворачивает. */
    static final class ThinkingHolder extends RecyclerView.ViewHolder {
        private final TextView text;
        private ChatItem current;

        ThinkingHolder(View view) {
            super(view);
            text = view.findViewById(R.id.text);
            view.setOnClickListener(v -> {
                if (current == null) return;
                current.expanded = !current.expanded;
                apply();
            });
        }

        void bind(ChatItem item) {
            current = item;
            text.setText(item.text);
            apply();
        }

        private void apply() {
            text.setMaxLines(current.expanded ? Integer.MAX_VALUE : 2);
        }
    }

    static final class ChartHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView source;
        private final FrameLayout container;

        ChartHolder(View view) {
            super(view);
            title = view.findViewById(R.id.title);
            source = view.findViewById(R.id.source);
            container = view.findViewById(R.id.chartContainer);
        }

        void bind(ChatItem item) {
            ChartSpec spec = item.chart;
            title.setText(spec.title);
            if (spec.source.isEmpty()) {
                source.setVisibility(View.GONE);
            } else {
                source.setVisibility(View.VISIBLE);
                source.setText(source.getContext().getString(R.string.chart_source, spec.source));
            }
            container.removeAllViews();
            container.addView(ChartRenderer.create(container.getContext(), spec),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
}
