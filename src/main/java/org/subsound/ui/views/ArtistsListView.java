package org.subsound.ui.views;

import org.subsound.app.state.AppManager;
import org.subsound.integration.ServerClient.ArtistAlbumInfo;
import org.subsound.integration.ServerClient.ArtistEntry;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.ui.components.RoundedAlbumArt;
import org.subsound.utils.Utils;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationSplitView;
import org.gnome.adw.StatusPage;
import org.gnome.gio.ListStore;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.types.Types;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.function.Consumer;

import static org.subsound.utils.Utils.cssClasses;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class ArtistsListView extends Box {
    private final ThumbnailCache thumbLoader;
    private final AppManager client;
    private final List<ArtistEntry> artists;
    private Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final NavigationSplitView view;
    private final NavigationPage initialPage;
    private final NavigationPage page1;
    private final NavigationPage contentPage;
    private final ArtistInfoLoader artistInfoLoader;
    private final ListStore<GArtistEntry> listModel;
    private final ListView listView;
    private final SingleSelection<GArtistEntry> selectionModel;
    private int currentIndex = -1;

    public ArtistsListView(
            ThumbnailCache thumbLoader,
            AppManager client,
            List<ArtistEntry> artists,
            Consumer<ArtistAlbumInfo> onAlbumSelected
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.client = client;
        this.artists = artists;
        this.onAlbumSelected = onAlbumSelected;
        this.artistInfoLoader = new ArtistInfoLoader(this.thumbLoader, this.client, albumInfo -> this.onAlbumSelected.accept(albumInfo));
        this.contentPage = NavigationPage.builder().setTag("page-2").setChild(this.artistInfoLoader).setTitle("ArtistView").build();

        var b = Box.builder().setValign(Align.CENTER).setHalign(Align.CENTER).build();
        b.append(Label.builder().setLabel("Select an artist to view").setCssClasses(cssClasses("title-1")).build());
        var statusPage = StatusPage.builder().setChild(b).build();
        this.initialPage = NavigationPage.builder().setTag("page-2-initial").setChild(statusPage).build();
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.view = NavigationSplitView.builder().setValign(Align.FILL).setHalign(Align.FILL).setHexpand(true).setVexpand(true).build();

        this.listModel = new ListStore<>(GArtistEntry.gtype);

        var factory = new SignalListItemFactory();
        factory.onSetup(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setActivatable(true);
            listitem.setChild(new ArtistRowWidget(this.client));
        });
        factory.onBind(object -> {
            ListItem listitem = (ListItem) object;
            var item = (GArtistEntry) listitem.getItem();
            if (item == null) {
                return;
            }
            if (listitem.getChild() instanceof ArtistRowWidget row) {
                row.bind(item);
            }
        });
        factory.onUnbind(object -> {
            ListItem listitem = (ListItem) object;
            if (listitem.getChild() instanceof ArtistRowWidget row) {
                row.unbind();
            }
        });
        factory.onTeardown(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setChild(null);
        });

        this.selectionModel = new SingleSelection<>(this.listModel);
        selectionModel.setAutoselect(false);
        selectionModel.setCanUnselect(true);

        this.listView = ListView.builder()
                .setShowSeparators(false)
                .setOrientation(VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setFocusOnClick(false)
                .setSingleClickActivate(true)
                .setFactory(factory)
                .setModel(selectionModel)
                .build();

        this.listView.onActivate(index -> {
            var item = this.listModel.getItem(index);
            if (item == null) {
                return;
            }
            this.currentIndex = index;
            var artist = item.getArtist();
            this.contentPage.setTitle(artist.name());
            this.setSelectedArtist(artist.id());
        });

        var motionController = new EventControllerMotion();
        motionController.onLeave(() -> {
            if (this.currentIndex >= 0) {
                this.selectionModel.setSelected(this.currentIndex);
            }
        });
        this.listView.addController(motionController);

        // Populate model
        var items = new GArtistEntry[this.artists.size()];
        for (int i = 0; i < this.artists.size(); i++) {
            items[i] = GArtistEntry.of(this.artists.get(i));
        }
        this.listModel.splice(0, 0, items);

        var artistView = ScrolledWindow.builder().setChild(this.listView).setHexpand(true).setVexpand(true).build();
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.page1 = NavigationPage.builder().setTag("page-1").setChild(artistView).setTitle("Artists").build();
        this.view.setSidebar(this.page1);
        this.view.setMaxSidebarWidth(300);
        this.view.setShowContent(true);
        this.view.setHexpand(true);
        this.view.setVexpand(true);
        this.view.setHalign(Align.FILL);
        this.view.setValign(Align.BASELINE_FILL);
        this.view.setContent(initialPage);
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(view);
    }

    public void setOnAlbumSelected(Consumer<ArtistAlbumInfo> onAlbumSelected) {
        this.onAlbumSelected = onAlbumSelected;
    }

    public void setSelectedArtist(String artistId) {
        this.artistInfoLoader.setArtistId(artistId);
        this.view.setContent(contentPage);
        this.view.setShowContent(true);
    }

    public static class GArtistEntry extends GObject {
        public static final Type gtype = Types.register(GArtistEntry.class);
        private ArtistEntry artist;

        public GArtistEntry(MemorySegment address) {
            super(address);
        }

        public static Type getType() {
            return gtype;
        }

        public static GArtistEntry of(ArtistEntry artist) {
            var instance = (GArtistEntry) GObject.newInstance(gtype);
            instance.artist = artist;
            return instance;
        }

        public ArtistEntry getArtist() {
            return artist;
        }
    }

    private static class ArtistRowWidget extends Box {
        private static final int ICON_SIZE = 48;

        private final AppManager appManager;
        private final RoundedAlbumArt prefixArt;
        private final Label titleLabel;
        private final Label subtitleLabel;

        public ArtistRowWidget(AppManager appManager) {
            super(HORIZONTAL, 12);
            this.appManager = appManager;

            this.setMarginTop(8);
            this.setMarginBottom(8);
            this.setMarginStart(12);
            this.setMarginEnd(12);

            this.prefixArt = new RoundedAlbumArt(java.util.Optional.empty(), appManager, ICON_SIZE);
            this.prefixArt.setHalign(CENTER);
            this.prefixArt.setValign(CENTER);

            var contentBox = new Box(VERTICAL, 2);
            contentBox.setHalign(START);
            contentBox.setValign(CENTER);
            contentBox.setHexpand(true);

            this.titleLabel = Label.builder()
                    .setLabel("")
                    .setHalign(START)
                    .setXalign(0)
                    .build();
            this.titleLabel.setSingleLineMode(true);
            this.titleLabel.setEllipsize(EllipsizeMode.END);

            this.subtitleLabel = Label.builder()
                    .setLabel("")
                    .setHalign(START)
                    .setXalign(0)
                    .setCssClasses(cssClasses("dim-label", "caption"))
                    .build();
            this.subtitleLabel.setSingleLineMode(true);

            contentBox.append(titleLabel);
            contentBox.append(subtitleLabel);

            this.append(prefixArt);
            this.append(contentBox);
        }

        public void bind(GArtistEntry entry) {
            var artist = entry.getArtist();
            this.titleLabel.setLabel(artist.name());
            this.subtitleLabel.setLabel("%d %s".formatted(artist.albumCount(), Utils.plural(artist.albumCount(), "album", "albums")));
            this.prefixArt.update(artist.coverArt());
        }

        public void unbind() {
            this.titleLabel.setLabel("");
            this.subtitleLabel.setLabel("");
        }
    }
}
