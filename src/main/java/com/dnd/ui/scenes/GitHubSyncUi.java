package com.dnd.ui.scenes;

import com.dnd.cli.storage.CampaignStorage;
import com.dnd.github.DeviceFlow;
import com.dnd.github.GitHubApi;
import com.dnd.github.GitHubLink;
import com.dnd.sync.CampaignRepo;
import com.dnd.sync.CampaignSyncService;
import com.dnd.ui.WindowOrder;
import com.dnd.ui.i18n.UiTranslator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The GitHub parts of the GM's screens: linking an account, connecting a campaign to a private
 * repository, downloading a campaign on another computer, and the small sync status badge.
 * All network and Git work runs in the background ({@link BaseScene#background} or
 * {@link CampaignSyncService}).
 */
final class GitHubSyncUi {
    private static final Logger LOGGER = Logger.getLogger(GitHubSyncUi.class.getName());
    static final String REPO_PREFIX = "almerion-campaign-";
    private static final String TOKEN_PAGE = "https://github.com/settings/personal-access-tokens/new";

    private GitHubSyncUi() {
    }

    // ---- account ------------------------------------------------------------------------------

    /** "GitHub: <login> [Unlink]" or "GitHub: not linked [Link GitHub Account...]". */
    static Node accountBar(BaseScene owner, Runnable changed) {
        Label caption = owner.body("GitHub:");
        Label login = UiTranslator.skip(owner.body("..."));
        HBox bar = new HBox(8, caption, login);
        bar.setAlignment(Pos.CENTER_LEFT);
        owner.background(() -> GitHubLink.shared().account(), account -> {
            if (account.isPresent()) {
                login.setText(account.get().login());
                bar.getChildren().add(owner.btn("Unlink", () -> confirmUnlink(owner, changed)));
            } else {
                bar.getChildren().remove(login);
                bar.getChildren().add(owner.body("not linked"));
                bar.getChildren().add(owner.btn("Link GitHub Account...", () -> linkAccount(owner, changed)));
            }
        }, error -> login.setText(BaseScene.friendlyError(error)));
        return bar;
    }

    private static void confirmUnlink(BaseScene owner, Runnable changed) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Unlink your GitHub account from Almerion on this computer? Campaign files stay on this computer and on"
                + " GitHub, but they won't sync until you link an account again.",
            ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Unlink GitHub Account");
        owner.styleDialog(confirm);
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> owner.background(
            unchecked(() -> { GitHubLink.shared().unlink(); return true; }),
            ok -> changed.run(), error -> owner.showError("Couldn't unlink the account", error)));
    }

    /** Runs {@code then} once an account is linked, asking the GM to link one first if needed. */
    static void requireAccount(BaseScene owner, Runnable then) {
        owner.background(() -> GitHubLink.shared().account(), account -> {
            if (account.isPresent()) {
                then.run();
                return;
            }
            Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                "Campaigns are synced through a private repository in your own GitHub account."
                    + " Link your GitHub account first.", ButtonType.OK, ButtonType.CANCEL);
            ask.setHeaderText("Link GitHub Account");
            owner.styleDialog(ask);
            ask.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> linkAccount(owner, then));
        }, error -> owner.showError("Couldn't read the linked GitHub account", error));
    }

    /** Device-flow sign-in when the app has an OAuth client ID, otherwise a pasted token. */
    static void linkAccount(BaseScene owner, Runnable linked) {
        GitHubLink link = GitHubLink.shared();
        if (!link.config().deviceFlowAvailable()) {
            pasteToken(owner, linked);
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        Stage stage = window("Link GitHub Account");
        Label code = UiTranslator.skip(owner.title("..."));
        Label status = owner.body("Asking GitHub for a sign-in code...");
        Button open = owner.btn("Copy Code and Open GitHub", () -> { });
        open.setDisable(true);
        Button token = owner.btn("Use a Token Instead...", () -> {
            stage.close();
            pasteToken(owner, linked);
        });
        Button cancel = owner.dangerBtn("Cancel", stage::close);
        stage.setOnHidden(e -> cancelled.set(true));
        VBox box = new VBox(14,
            owner.body("Almerion will save your campaigns to private repositories in your own GitHub account,"
                + " so they stay in step on every computer you use."),
            owner.body("1. Copy this code.  2. Sign in on github.com and enter it.  3. Approve the request."),
            code, status, flow(open, token, cancel));
        box.setPadding(new Insets(20));
        stage.setScene(owner.themedScene(box, 560, 360));
        stage.show();

        DeviceFlow deviceFlow = new DeviceFlow(link.config().clientId(), link.http());
        owner.background(unchecked(deviceFlow::start), started -> {
            code.setText(started.userCode());
            status.setText("Waiting for you to approve the request on GitHub...");
            open.setDisable(false);
            open.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(started.userCode());
                Clipboard.getSystemClipboard().setContent(content);
                browse(started.verificationUri());
            });
            owner.background(unchecked(() -> link.link(deviceFlow.awaitToken(started, cancelled::get))), account -> {
                stage.close();
                owner.showInfo("GitHub account linked", "Linked to GitHub as " + account.login() + ".");
                linked.run();
            }, error -> {
                if (!(error instanceof CancellationException)) status.setText(explain(error));
            });
        }, error -> status.setText(explain(error)));
    }

    private static void pasteToken(BaseScene owner, Runnable linked) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Link GitHub Account");
        dialog.setHeaderText("Paste a GitHub access token");
        PasswordField field = owner.passwordField("github_pat_...");
        VBox box = new VBox(10,
            owner.body("Almerion syncs campaigns through private repositories in your own GitHub account."
                + " To allow that, create a fine-grained personal access token on GitHub:"),
            owner.body("• Repository access: All repositories (or only your campaign repositories).\n"
                + "• Repository permissions: Contents - Read and write; Administration - Read and write"
                + " (only needed to create new repositories from Almerion).\n"
                + "• Pick an expiry date you are comfortable with; link again when it runs out."),
            owner.btn("Open the GitHub Token Page", () -> browse(TOKEN_PAGE)),
            owner.body("The token is stored only on this computer, for your Windows user."),
            field);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        owner.styleDialog(dialog);
        Platform.runLater(field::requestFocus);
        Optional<ButtonType> choice = dialog.showAndWait();
        String token = field.getText() == null ? "" : field.getText().trim();
        if (choice.orElse(ButtonType.CANCEL) != ButtonType.OK || token.isEmpty()) return;
        owner.background(unchecked(() -> GitHubLink.shared().link(token)), account -> {
            owner.showInfo("GitHub account linked", "Linked to GitHub as " + account.login() + ".");
            linked.run();
        }, error -> owner.showInfo("Couldn't link the GitHub account", explain(error)));
    }

    // ---- per-campaign sync --------------------------------------------------------------------

    /** "Sync with GitHub...": manage an already synced campaign, or connect it to a repository. */
    static void configureCampaign(BaseScene owner, String folderName, Path folder, Runnable changed) {
        requireAccount(owner, () -> owner.background(() -> CampaignRepo.isSynced(folder), synced -> {
            if (synced) {
                manageSynced(owner, folderName, folder, changed);
            } else {
                connectCampaign(owner, folderName, folder, changed);
            }
        }, error -> owner.showError("Couldn't read the campaign's sync settings", error)));
    }

    private static void manageSynced(BaseScene owner, String folderName, Path folder, Runnable changed) {
        String repo = CampaignRepo.displayName(CampaignRepo.remoteUrl(folder).orElse(""));
        ButtonType syncNow = new ButtonType("Sync Now", ButtonBar.ButtonData.OK_DONE);
        ButtonType stop = new ButtonType("Stop Syncing", ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "", syncNow, stop, ButtonType.CLOSE);
        alert.setHeaderText("This campaign syncs with GitHub");
        Label repoLabel = UiTranslator.skip(owner.sectionLabel(repo));
        Label state = owner.body(CampaignSyncService.shared().status(folder)
            .map(CampaignSyncService.Status::message).orElse("Synced automatically when you open and leave it."));
        alert.getDialogPane().setContent(new VBox(8, owner.body("Repository:"), repoLabel, state));
        alert.getDialogPane().setPrefWidth(520);
        owner.styleDialog(alert);
        ButtonType choice = alert.showAndWait().orElse(ButtonType.CLOSE);
        if (choice == syncNow) {
            CampaignSyncService.shared().syncNow(folder).thenAccept(result -> Platform.runLater(() -> {
                showResult(owner, folderName, result);
                changed.run();
            }));
        } else if (choice == stop) {
            owner.background(unchecked(() -> { CampaignRepo.setSyncEnabled(folder, false); return true; }),
                ok -> changed.run(), error -> owner.showError("Couldn't stop syncing", error));
        }
    }

    private static void connectCampaign(BaseScene owner, String folderName, Path folder, Runnable changed) {
        Stage stage = window("Sync \"" + folderName + "\" with GitHub");
        TextField name = owner.textField("Repository name");
        name.setText(REPO_PREFIX + folderName);
        UiTranslator.skip(name);
        Label status = owner.body("");
        ListView<GitHubApi.Repo> repos = repoList();
        Button create = owner.btn("Create Private Repository and Upload", () -> { });
        Button useExisting = owner.btn("Use Selected Repository", () -> { });
        useExisting.disableProperty().bind(repos.getSelectionModel().selectedItemProperty().isNull());

        Runnable busy = () -> {
            create.setDisable(true);
            useExisting.disableProperty().unbind();
            useExisting.setDisable(true);
        };
        BiConsumer<GitHubApi.Repo, Boolean> connect = (repo, created) -> {
            status.setText(created ? "Uploading the campaign..." : "Syncing the campaign with the repository...");
            CampaignSyncService.shared().connect(folder, repo.cloneUrl()).thenAccept(result -> Platform.runLater(() -> {
                stage.close();
                showResult(owner, folderName, result);
                changed.run();
            }));
        };
        create.setOnAction(e -> {
            String repoName = name.getText() == null ? "" : name.getText().trim();
            if (!repoName.matches("[A-Za-z0-9._-]{1,100}")) {
                status.setText("Use only letters, numbers, dots, dashes and underscores in the repository name.");
                return;
            }
            busy.run();
            status.setText("Creating the private repository...");
            owner.background(unchecked(() -> GitHubLink.shared().api()
                    .createPrivateRepo(repoName, "Almerion campaign: " + folderName)),
                repo -> connect.accept(repo, true),
                error -> {
                    status.setText(explain(error));
                    create.setDisable(false);
                });
        });
        useExisting.setOnAction(e -> {
            GitHubApi.Repo repo = repos.getSelectionModel().getSelectedItem();
            if (repo == null) return;
            if (!repo.empty() && !confirmMerge(owner, repo)) return;
            busy.run();
            connect.accept(repo, false);
        });

        VBox box = new VBox(10,
            owner.body("The campaign is uploaded to a private repository in your GitHub account. From then on it"
                + " syncs automatically: changes from your other computers are fetched when you open it, and"
                + " yours are uploaded while you work and when you leave it."),
            owner.sectionLabel("New private repository"),
            name, create,
            owner.sectionLabel("Or an existing repository"),
            repos, useExisting, status);
        box.setPadding(new Insets(20));
        VBox.setVgrow(repos, Priority.ALWAYS);
        stage.setScene(owner.themedScene(box, 600, 560));
        stage.show();
        loadRepos(owner, repos, status);
    }

    private static boolean confirmMerge(BaseScene owner, GitHubApi.Repo repo) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "This repository already has files. They will be combined with this campaign. Where both have the"
                + " same file, this computer's version is kept and GitHub's version is saved next to it with"
                + " \".remote-\" and the date added to its name. Continue?",
            ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Combine with an existing repository");
        owner.styleDialog(confirm);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** "Get Campaign from GitHub...": download a synced campaign onto this computer. */
    static void downloadCampaign(BaseScene owner, CampaignStorage storage, Runnable changed) {
        requireAccount(owner, () -> {
            Stage stage = window("Get a Campaign from GitHub");
            ListView<GitHubApi.Repo> repos = repoList();
            TextField folderName = owner.textField("Campaign folder name");
            UiTranslator.skip(folderName);
            Label status = owner.body("");
            repos.getSelectionModel().selectedItemProperty().addListener((obs, old, repo) -> {
                if (repo != null) folderName.setText(storage.normalizeCampaignName(stripPrefix(repo.name())));
            });
            Button download = owner.btn("Download Campaign", () -> { });
            download.disableProperty().bind(repos.getSelectionModel().selectedItemProperty().isNull());
            download.setOnAction(e -> {
                GitHubApi.Repo repo = repos.getSelectionModel().getSelectedItem();
                String name = storage.normalizeCampaignName(folderName.getText());
                if (repo == null || name.isEmpty()) return;
                if (storage.listCustomCampaigns().contains(name)) {
                    status.setText("A campaign with that folder name is already on this computer. Pick another name.");
                    return;
                }
                download.disableProperty().unbind();
                download.setDisable(true);
                status.setText("Downloading...");
                CampaignSyncService.shared().download(repo.cloneUrl(), storage.resolveCustomCampaignPath(name))
                    .thenAccept(result -> Platform.runLater(() -> {
                        if (result.outcome() == CampaignRepo.Outcome.SYNCED) {
                            stage.close();
                            owner.showInfo("Campaign downloaded", "\"" + name + "\" is now on this computer and"
                                + " syncs with GitHub automatically. If it isn't in your list yet, claim it below"
                                + " or ask its author to add you as a Game Master.");
                            changed.run();
                        } else {
                            status.setText(result.message());
                            download.setDisable(false);
                        }
                    }));
            });
            VBox box = new VBox(10,
                owner.body("Pick the repository you synced the campaign to from your other computer."),
                repos, owner.body("Save it on this computer as:"), folderName, download, status);
            box.setPadding(new Insets(20));
            VBox.setVgrow(repos, Priority.ALWAYS);
            stage.setScene(owner.themedScene(box, 560, 520));
            stage.show();
            loadRepos(owner, repos, status);
        });
    }

    private static String stripPrefix(String repoName) {
        return repoName.startsWith(REPO_PREFIX) ? repoName.substring(REPO_PREFIX.length()) : repoName;
    }

    private static ListView<GitHubApi.Repo> repoList() {
        ListView<GitHubApi.Repo> list = UiTranslator.skip(new ListView<>());
        list.getStyleClass().add("dnd-list-view");
        list.setPrefHeight(220);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(GitHubApi.Repo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.fullName() + (item.isPrivate() ? "  🔒" : "  (public)")
                    + (item.description().isBlank() ? "" : "\n" + item.description()));
            }
        });
        return list;
    }

    /** Fills {@code list} with the GM's repositories, Almerion campaign repositories first. */
    private static void loadRepos(BaseScene owner, ListView<GitHubApi.Repo> list, Label status) {
        status.setText("Loading your repositories...");
        owner.background(unchecked(() -> GitHubLink.shared().api().repositories()), repos -> {
            List<GitHubApi.Repo> sorted = repos.stream()
                .sorted(Comparator.comparing((GitHubApi.Repo r) -> !r.name().startsWith(REPO_PREFIX)))
                .toList();
            list.getItems().setAll(sorted);
            status.setText(sorted.isEmpty() ? "You don't have any repositories on GitHub yet." : "");
        }, error -> status.setText(explain(error)));
    }

    static void showResult(BaseScene owner, String folderName, CampaignRepo.Result result) {
        switch (result.outcome()) {
            case SYNCED -> owner.showInfo("Synced with GitHub", "\"" + folderName + "\" is up to date on GitHub.");
            case REMOTE_AHEAD, OFFLINE -> owner.showInfo("Will sync later", result.message());
            case CONFLICT -> owner.showInfo("Both computers changed the same files", result.message());
            default -> owner.showInfo("Sync problem", result.message());
        }
    }

    // ---- status badge -------------------------------------------------------------------------

    /** A small label showing the campaign's sync state; hidden for campaigns that don't sync. */
    static Label statusBadge(BaseScene owner, Path folder) {
        Label badge = new Label();
        badge.setVisible(false);
        badge.setManaged(false);
        Path key = CampaignSyncService.key(folder);
        CampaignSyncService service = CampaignSyncService.shared();
        BiConsumer<Path, CampaignSyncService.Status> listener = (path, status) -> {
            if (path.equals(key)) Platform.runLater(() -> apply(badge, status));
        };
        service.addListener(listener);
        badge.sceneProperty().addListener((obs, old, scene) -> {
            if (old != null && scene == null) service.removeListener(listener);
        });
        badge.setOnMouseClicked(e -> service.status(folder).ifPresent(status ->
            owner.showInfo("GitHub sync", status.message())));
        owner.background(() -> CampaignRepo.isSynced(folder), synced -> {
            if (!synced) return;
            badge.setVisible(true);
            badge.setManaged(true);
            Optional<CampaignSyncService.Status> current = service.status(folder);
            if (current.isPresent()) {
                apply(badge, current.get());
            } else {
                badge.setText("GitHub sync on");
                badge.getStyleClass().setAll("label", "body-label");
            }
        }, error -> { });
        return badge;
    }

    private static void apply(Label badge, CampaignSyncService.Status status) {
        badge.setVisible(true);
        badge.setManaged(true);
        badge.setTooltip(new Tooltip(status.message()));
        switch (status.state()) {
            case SYNCING -> {
                badge.setText("⟳ Syncing...");
                badge.getStyleClass().setAll("label", "body-label");
            }
            case SYNCED -> {
                badge.setText("✓ Synced");
                badge.getStyleClass().setAll("label", "success-badge");
            }
            case OFFLINE -> {
                badge.setText("⚠ Offline - will retry");
                badge.getStyleClass().setAll("label", "error-label");
            }
            case CONFLICT -> {
                badge.setText("⚠ Conflict - both versions kept");
                badge.getStyleClass().setAll("label", "error-label");
            }
            case ERROR -> {
                badge.setText("⚠ Sync problem");
                badge.getStyleClass().setAll("label", "error-label");
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static Stage window(String title) {
        Stage stage = new Stage();
        stage.setTitle(title);
        stage.initOwner(WindowOrder.current());
        stage.initModality(Modality.WINDOW_MODAL);
        return stage;
    }

    private static FlowPane flow(Node... nodes) {
        FlowPane pane = new FlowPane(10, 10, nodes);
        pane.setAlignment(Pos.CENTER_LEFT);
        return pane;
    }

    static void browse(String url) {
        Thread thread = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not open " + url, e);
            }
        }, "open-github");
        thread.setDaemon(true);
        thread.start();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable t = error;
        while (t instanceof WrappedException && t.getCause() != null) t = t.getCause();
        return t;
    }

    /** A message the GM can act on; raw network exceptions become "can't reach GitHub". */
    private static String explain(Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof java.io.IOException) {
            return "Can't reach GitHub. Check your internet connection and try again.";
        }
        return BaseScene.friendlyError(cause);
    }

    /** Lets checked-exception work run through {@link BaseScene#background}. */
    private static <T> java.util.function.Supplier<T> unchecked(Callable<T> work) {
        return () -> {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new WrappedException(e);
            }
        };
    }

    private static final class WrappedException extends RuntimeException {
        WrappedException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
