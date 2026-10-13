package com.example.aionfinances;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Единственный экран приложения: чат с ассистентом. */
public class MainActivity extends AppCompatActivity implements ChatViewModel.Listener {

    private ChatViewModel viewModel;
    private ChatAdapter adapter;

    private RecyclerView list;
    private TextView empty;
    private EditText input;
    private ImageButton send;
    private View filesScroll;
    private LinearLayout filesRow;

    /** Системное окно выбора файлов (в нём есть и Google Диск); результат приходит в onFilesPicked. */
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::onFilesPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setUpSystemBars();

        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        input = findViewById(R.id.input);
        send = findViewById(R.id.send);
        filesScroll = findViewById(R.id.filesScroll);
        filesRow = findViewById(R.id.filesRow);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // новые сообщения прижаты к низу, как в мессенджерах
        list.setLayoutManager(layoutManager);
        list.setItemAnimator(null); // без анимации: текст обновляется много раз в секунду
        adapter = new ChatAdapter(viewModel.items);
        list.setAdapter(adapter);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_new_chat) {
                if (viewModel.isBusy()) {
                    toast(R.string.wait_answer);
                } else {
                    viewModel.clearChat();
                }
                return true;
            }
            if (item.getItemId() == R.id.action_history) {
                showHistoryDialog();
                return true;
            }
            if (item.getItemId() == R.id.action_delete_chat) {
                if (viewModel.isBusy()) {
                    toast(R.string.wait_answer);
                } else {
                    viewModel.deleteCurrentChat();
                }
                return true;
            }
            if (item.getItemId() == R.id.action_api_key) {
                showApiKeyDialog();
                return true;
            }
            return false;
        });

        send.setOnClickListener(v -> onSendClicked());
        findViewById(R.id.attach).setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));

        viewModel.setListener(this);
        onBusyChanged(viewModel.isBusy());
        refreshEmptyState();
        refreshPendingFiles();

        if (Config.USE_MOCK) {
            toolbar.setSubtitle(R.string.subtitle_mock);
        } else if (Prefs.getApiKey(this).isEmpty()) {
            showApiKeyDialog();
        }
    }

    @Override
    protected void onDestroy() {
        viewModel.setListener(null);
        super.onDestroy();
    }

    /**
     * Приложение рисуется под строкой состояния и панелью навигации («от края до края»),
     * поэтому сами добавляем отступы под системные панели и клавиатуру.
     */
    private void setUpSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        boolean light = getResources().getBoolean(R.bool.light_system_bars);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }

    private void onSendClicked() {
        if (viewModel.isBusy()) {
            toast(R.string.wait_answer);
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty() && viewModel.pendingFiles.isEmpty()) return;

        String apiKey = Prefs.getApiKey(this);
        if (apiKey.isEmpty() && !Config.USE_MOCK) {
            toast(R.string.need_api_key);
            showApiKeyDialog();
            return;
        }
        input.setText("");
        viewModel.send(text, apiKey);
        refreshPendingFiles();
    }

    private void onFilesPicked(List<Uri> uris) {
        if (uris == null) return;
        for (Uri uri : uris) {
            String name = AttachmentReader.displayName(getContentResolver(), uri);
            viewModel.pendingFiles.add(new ChatViewModel.PendingFile(uri, name));
        }
        refreshPendingFiles();
    }

    /** Рисует над полем ввода «плашки» с выбранными файлами; нажатие на плашку убирает файл. */
    private void refreshPendingFiles() {
        filesRow.removeAllViews();
        for (ChatViewModel.PendingFile file : viewModel.pendingFiles) {
            TextView chip = new TextView(this);
            chip.setText(file.name + "  ✕");
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTextColor(ContextCompat.getColor(this, R.color.on_sea_container));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setMaxLines(1);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setOnClickListener(v -> {
                viewModel.pendingFiles.remove(file);
                refreshPendingFiles();
            });
            filesRow.addView(chip, lp);
        }
        filesScroll.setVisibility(viewModel.pendingFiles.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Список сохранённых чатов; нажатие открывает чат. */
    private void showHistoryDialog() {
        if (viewModel.isBusy()) {
            toast(R.string.wait_answer);
            return;
        }
        final List<ChatStore.Entry> entries = viewModel.store.list();
        if (entries.isEmpty()) {
            toast(R.string.history_empty);
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());
        CharSequence[] labels = new CharSequence[entries.size()];
        for (int i = 0; i < labels.length; i++) {
            ChatStore.Entry e = entries.get(i);
            labels[i] = format.format(new Date(e.updated)) + " · " + e.title;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_title)
                .setItems(labels, (dialog, which) -> viewModel.loadChat(entries.get(which).id))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showApiKeyDialog() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint("sk-ant-…");
        field.setText(Prefs.getApiKey(this));

        FrameLayout box = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(24), dp(8), dp(24), 0);
        box.addView(field, lp);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.api_key_title)
                .setMessage(R.string.api_key_message)
                .setView(box)
                .setPositiveButton(R.string.save,
                        (dialog, which) -> Prefs.setApiKey(this, field.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- События от ChatViewModel ---

    @Override
    public void onItemAdded(int index) {
        adapter.notifyItemInserted(index);
        list.scrollToPosition(index);
        refreshEmptyState();
    }

    @Override
    public void onItemChanged(int index) {
        // Если лента была прокручена до конца, держим её у конца, пока текст растёт.
        boolean atBottom = !list.canScrollVertically(1);
        adapter.notifyItemChanged(index);
        if (atBottom) list.post(() -> list.scrollBy(0, 100_000));
    }

    @Override
    public void onItemRemoved(int index) {
        adapter.notifyItemRemoved(index);
        refreshEmptyState();
    }

    @Override
    public void onChatReplaced() {
        adapter.notifyDataSetChanged();
        if (!viewModel.items.isEmpty()) list.scrollToPosition(viewModel.items.size() - 1);
        refreshEmptyState();
        refreshPendingFiles();
    }

    @Override
    public void onBusyChanged(boolean busy) {
        send.setAlpha(busy ? 0.4f : 1f);
    }

    @Override
    public void onMessage(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void refreshEmptyState() {
        empty.setVisibility(viewModel.items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void toast(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
