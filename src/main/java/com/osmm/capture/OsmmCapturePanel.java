package com.osmm.capture;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sidebar panel: start and stop a recording, add notes to it, and list the recordings, with each finished one's
 * label and notes to edit (saved to its sidecar). Swing work only, on the EDT: the recording is driven through the
 * plugin (which hands it to the client thread), and files are read and written on the plugin's file thread.
 */
final class OsmmCapturePanel extends PluginPanel
{
	private static final Logger log = LoggerFactory.getLogger(OsmmCapturePanel.class);
	private static final Color WHITE = Color.WHITE;
	private static final Color GREY = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color RED = new Color(230, 80, 80);
	private static final ImageIcon CROSS = new ImageIcon(cross());

	private final OsmmCapturePlugin plugin;
	private final CaptureFiles files;
	private final ExecutorService io;
	private final Timer timer;

	// status
	private final JLabel statusLine = small("Not recording", WHITE);
	private final JLabel timeLine = small("", GREY);
	private final JLabel labelCaption = small("Label (optional)", GREY);
	private final JTextField labelField = new JTextField();
	private final JButton startStop = new JButton("Start");
	private final JLabel message = small("", RED);

	// while recording
	private final JPanel liveBox = box();
	private final JTextField noteField = new JTextField();
	private final JPanel liveNotes = new JPanel(new GridLayout(0, 1, 0, 1));

	// recordings
	private final DefaultListModel<CaptureFiles.Entry> model = new DefaultListModel<>();
	private final JList<CaptureFiles.Entry> list = new JList<CaptureFiles.Entry>(model)
	{
		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;   // cells fit the panel, cut with "..." rather than running off it
		}
	};
	private final JLabel listInfo = small("Reading...", GREY);
	private JScrollPane listScroll;

	// the main view, hidden while a recording's open
	private final JPanel mainView = new JPanel(new GridBagLayout());

	// the selected recording
	private final JPanel editor = box();
	private final JLabel editorName = small("", GREY);
	private final JLabel editorInfo = small("", GREY);
	private final JPanel editFields = new JPanel(new GridBagLayout());
	private final JTextField editLabel = new JTextField();
	private final JPanel noteRows = new JPanel(new GridLayout(0, 1, 0, 2));
	private final JLabel noNotes = small("No notes.", GREY);
	private final List<NoteRow> rows = new ArrayList<>();
	private final JLabel editMessage = small("", GREY);
	private final JButton deleteButton = new JButton("Delete");

	private String shownFile;                    // the recording the status shows (null: none)
	private int shownNotes = -1;
	private CaptureFiles.Entry selected;          // in the editor
	private String loadedLabel = "";
	private List<CaptureFiles.Note> loadedNotes = Collections.emptyList();
	private boolean applying;                     // refilling the list: ignore its selection events

	OsmmCapturePanel(OsmmCapturePlugin plugin, CaptureFiles files, ExecutorService io)
	{
		this.plugin = plugin;
		this.files = files;
		this.io = io;

		setLayout(new GridBagLayout());
		int y = 0;
		JLabel title = new JLabel("OSMM capture");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(WHITE);
		add(title, row(y++, 0));
		// Two views: the main one (status, notes, the recordings) and a picked recording's, with Back to return.
		mainView.setOpaque(false);
		int m = 0;
		mainView.add(statusBox(), row(m++, 0));
		mainView.add(liveBox(), row(m++, 6));
		mainView.add(recordingsBox(), row(m++, 10));
		JButton open = new JButton("Open folder");
		open.setToolTipText("The folder the recordings are saved in");
		open.addActionListener(e -> openFolder());
		mainView.add(open, row(m++, 6));
		add(mainView, row(y++, 6));
		add(editorBox(), row(y++, 6));

		statusChanged();
		timer = new Timer(1000, e -> statusChanged());
		timer.start();
		refreshRecordings();
	}

	/** The plugin's stopping: no more polling. */
	void dispose()
	{
		timer.stop();
	}

	@Override
	public void onActivate()
	{
		refreshRecordings();
	}

	// ---- layout -----------------------------------------------------------------------------------------

	private JPanel statusBox()
	{
		JPanel p = box();
		int y = 0;
		p.add(statusLine, row(y++, 0));
		p.add(timeLine, row(y++, 0));
		p.add(labelCaption, row(y++, 6));
		p.add(labelField, row(y++, 2));
		p.add(startStop, row(y++, 6));
		p.add(message, row(y++, 4));
		labelField.setToolTipText("Goes in the file's header, e.g. tbow sounds");
		labelField.addActionListener(e -> startOrStop());
		startStop.addActionListener(e -> startOrStop());
		return p;
	}

	private JPanel liveBox()
	{
		JPanel p = liveBox;
		int y = 0;
		p.add(caption("Note"), row(y++, 0));
		p.add(noteField, row(y++, 2));
		JButton add = new JButton("Add note");
		p.add(add, row(y++, 4));
		p.add(liveNotes, row(y++, 6));
		liveNotes.setOpaque(false);
		noteField.setToolTipText("Marks this moment, e.g. barrage while walking");
		noteField.addActionListener(e -> addLiveNote());
		add.addActionListener(e -> addLiveNote());
		p.setVisible(false);
		return p;
	}

	private JPanel recordingsBox()
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		p.add(caption("Recordings"), row(0, 0));
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new EntryRenderer());
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setVisibleRowCount(4);
		list.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting() && !applying)
			{
				select(list.getSelectedValue());
			}
		});
		listScroll = new JScrollPane(list);
		listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		listScroll.setBorder(BorderFactory.createEmptyBorder());
		listScroll.setVisible(false);
		p.add(listScroll, row(1, 4));
		p.add(listInfo, row(2, 4));
		return p;
	}

	private JPanel editorBox()
	{
		JPanel p = editor;
		int y = 0;
		JButton back = new JButton("Back");
		back.setToolTipText("Back to the recordings");
		back.addActionListener(e -> back());
		p.add(back, row(y++, 0));
		p.add(editorName, row(y++, 6));
		p.add(editorInfo, row(y++, 4));

		editFields.setOpaque(false);
		int f = 0;
		editFields.add(caption("Label"), row(f++, 0));
		editFields.add(editLabel, row(f++, 2));
		JPanel heads = new JPanel(new BorderLayout(4, 0));
		heads.setOpaque(false);
		JLabel tickHead = caption("Tick");
		tickHead.setPreferredSize(new Dimension(44, tickHead.getPreferredSize().height));
		heads.add(tickHead, BorderLayout.WEST);
		heads.add(caption("Notes"), BorderLayout.CENTER);
		editFields.add(heads, row(f++, 8));
		noteRows.setOpaque(false);
		editFields.add(noteRows, row(f++, 2));
		editFields.add(noNotes, row(f++, 2));
		JPanel noteButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		noteButtons.setOpaque(false);
		JButton addNote = new JButton("Add note");
		JButton save = new JButton("Save");
		noteButtons.add(addNote);
		noteButtons.add(save);
		editFields.add(noteButtons, row(f++, 6));
		editFields.add(editMessage, row(f++, 4));
		p.add(editFields, row(y++, 0));

		p.add(deleteButton, row(y++, 8));

		addNote.setToolTipText("A note at a tick of your choice");
		save.setToolTipText("Saves the label and notes beside the recording; the recording itself isn't changed");
		addNote.addActionListener(e -> addRow(new CaptureFiles.Note(rows.isEmpty() ? 0 : rows.get(rows.size() - 1).tick(), ""), true));
		save.addActionListener(e -> save());
		deleteButton.addActionListener(e -> delete());
		p.setVisible(false);
		return p;
	}

	// ---- status and live notes ----------------------------------------------------------------------------

	/** Shows the plugin's status (each second, and at once when it changes). */
	void statusChanged()
	{
		OsmmCapturePlugin.Status s = plugin.status();
		boolean recording = s.file != null;
		if (recording)
		{
			setText(statusLine, "Recording: " + s.file);
			setText(timeLine, CaptureFiles.ticksText(s.ticks));
		}
		else
		{
			setText(statusLine, s.waiting ? "Waiting for combat" : "Not recording");
		}
		timeLine.setVisible(recording);
		labelCaption.setVisible(!recording);
		labelField.setVisible(!recording);
		setText(startStop, recording ? "Stop" : "Start");
		liveBox.setVisible(recording);
		if (s.notes.size() != shownNotes || !Objects.equals(s.file, shownFile))
		{
			shownNotes = s.notes.size();
			liveNotes.removeAll();
			if (s.notes.isEmpty())
			{
				liveNotes.add(small("No notes yet.", GREY));
			}
			for (CaptureFiles.Note n : s.notes)
			{
				JLabel l = small("Tick " + n.tick + ": " + n.text, WHITE);
				l.setToolTipText(n.text);
				liveNotes.add(l);
			}
			revalidate();
			repaint();
		}
		if (!Objects.equals(s.file, shownFile))
		{
			if (recording)
			{
				message.setText("");
				labelField.setText("");
			}
			shownFile = s.file;
			refreshRecordings();   // the new file, or the finished one
		}
	}

	void showMessage(String text)
	{
		message.setText(text);
	}

	private void startOrStop()
	{
		message.setText("");
		if (plugin.status().file != null)
		{
			plugin.panelStop();
		}
		else
		{
			plugin.panelStart(labelField.getText().trim());
		}
	}

	private void addLiveNote()
	{
		String text = noteField.getText().trim();
		if (text.isEmpty())
		{
			return;
		}
		plugin.panelNote(text);
		noteField.setText("");
	}

	// ---- the recordings list ------------------------------------------------------------------------------

	/** Reads the folder again (on the file thread) and shows what's there. */
	void refreshRecordings()
	{
		String recordingName = plugin.status().file;
		run(() ->
		{
			try
			{
				List<CaptureFiles.Entry> entries = files.list(recordingName);
				SwingUtilities.invokeLater(() -> showRecordings(entries));
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("osmm-capture: can't list the folder", e);
				SwingUtilities.invokeLater(() -> listInfo.setText("Couldn't read the folder: " + e.getMessage()));
			}
		});
	}

	private void showRecordings(List<CaptureFiles.Entry> entries)
	{
		String keep = selected != null ? selected.name : null;
		applying = true;
		try
		{
			model.clear();
			CaptureFiles.Entry again = null;
			for (CaptureFiles.Entry e : entries)
			{
				model.addElement(e);
				if (e.name.equals(keep))
				{
					again = e;
				}
			}
			if (again != null)
			{
				list.setSelectedValue(again, false);
				// The same one: keep any edits in progress. Load it afresh when it has just stopped recording (now
				// editable), or when it says something new and nothing's been typed (its last lines reached the disk).
				boolean finished = selected.recording && !again.recording;
				boolean newer = !again.recording && !changed()
					&& (!again.label().equals(loadedLabel) || !again.notes().equals(loadedNotes));
				selected = again;
				if (finished || newer)
				{
					load(again);
				}
				else
				{
					showEditorState();
				}
			}
			else if (keep != null)
			{
				selected = null;   // gone from the folder
				showRecording(false);
			}
		}
		finally
		{
			applying = false;
		}
		listInfo.setText(entries.isEmpty() ? "No recordings yet." : "");
		listInfo.setVisible(entries.isEmpty());
		listScroll.setVisible(!entries.isEmpty());
		revalidate();
		repaint();
	}

	private void select(CaptureFiles.Entry e)
	{
		if (selected != null && e != null && selected.name.equals(e.name))
		{
			return;
		}
		if (selected != null && !selected.recording && changed())
		{
			int answer = JOptionPane.showOptionDialog(this,
				"Discard the changes to " + selected.name + "?", "Unsaved changes",
				JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
				new Object[]{"Discard", "Keep editing"}, "Keep editing");
			if (answer != 0)
			{
				applying = true;
				list.setSelectedValue(selected, false);
				applying = false;
				return;
			}
		}
		if (e == null)
		{
			selected = null;
			showRecording(false);
			return;
		}
		load(e);
	}

	/** Back to the main view, asking first if there are edits not saved. */
	private void back()
	{
		if (selected != null && !selected.recording && changed())
		{
			int answer = JOptionPane.showOptionDialog(this,
				"Discard the changes to " + selected.name + "?", "Unsaved changes",
				JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
				new Object[]{"Discard", "Keep editing"}, "Keep editing");
			if (answer != 0)
			{
				return;
			}
		}
		selected = null;
		applying = true;   // (so picking the same recording again opens it)
		list.clearSelection();
		applying = false;
		showRecording(false);
	}

	/** The picked recording's view, or the main one. */
	private void showRecording(boolean open)
	{
		mainView.setVisible(!open);
		editor.setVisible(open);
		revalidate();
		repaint();
	}

	// ---- the editor ---------------------------------------------------------------------------------------

	private void load(CaptureFiles.Entry e)
	{
		selected = e;
		loadedLabel = e.label();
		loadedNotes = new ArrayList<>(e.notes());
		editLabel.setText(loadedLabel);
		rows.clear();
		noteRows.removeAll();
		for (CaptureFiles.Note n : loadedNotes)
		{
			addRow(n, false);
		}
		editMessage.setText(e.edits != null ? "Label and notes as edited." : "");
		editMessage.setForeground(GREY);
		showEditorState();
		showRecording(true);
	}

	private void showEditorState()
	{
		CaptureFiles.Entry e = selected;
		editorName.setText(e.name);
		editorName.setToolTipText(e.name);
		if (e.recording)
		{
			editorInfo.setText("Recording now: add notes on the main view.");
		}
		else
		{
			String length = e.file == null || e.file.ticks < 0 ? "length unknown" : CaptureFiles.ticksText(e.file.ticks);
			editorInfo.setText(e.file != null && !e.file.ended ? length + ", cut short" : length);
		}
		editFields.setVisible(!e.recording);
		deleteButton.setEnabled(!e.recording);
		deleteButton.setToolTipText(e.recording ? "Stop the recording first" : "Deletes the recording and its notes");
		noNotes.setVisible(rows.isEmpty());
	}

	private void addRow(CaptureFiles.Note n, boolean focus)
	{
		NoteRow r = new NoteRow(n);
		rows.add(r);
		noteRows.add(r.panel);
		noNotes.setVisible(false);
		revalidate();
		repaint();
		if (focus)
		{
			r.text.requestFocusInWindow();
		}
	}

	private void removeRow(NoteRow r)
	{
		rows.remove(r);
		noteRows.remove(r.panel);
		noNotes.setVisible(rows.isEmpty());
		revalidate();
		repaint();
	}

	/** The notes as typed, or null when a tick isn't a whole number from 0. */
	private List<CaptureFiles.Note> typedNotes()
	{
		List<CaptureFiles.Note> out = new ArrayList<>();
		for (NoteRow r : rows)
		{
			int t = r.tick();
			if (t < 0)
			{
				return null;
			}
			out.add(new CaptureFiles.Note(t, r.text.getText().trim()));
		}
		return out;
	}

	private boolean changed()
	{
		List<CaptureFiles.Note> typed = typedNotes();
		return typed == null || !typed.equals(loadedNotes) || !editLabel.getText().trim().equals(loadedLabel);
	}

	private void save()
	{
		CaptureFiles.Entry e = selected;
		List<CaptureFiles.Note> typed = typedNotes();
		if (e == null || e.recording)
		{
			return;
		}
		if (typed == null)
		{
			editMessage.setForeground(RED);
			editMessage.setText("Ticks must be whole numbers from 0.");
			return;
		}
		typed.removeIf(n -> n.text.isEmpty());
		String label = editLabel.getText().trim();
		editMessage.setForeground(GREY);
		editMessage.setText("Saving...");
		run(() ->
		{
			try
			{
				files.saveEdits(e.name, label, typed);
				SwingUtilities.invokeLater(() ->
				{
					if (selected != null && selected.name.equals(e.name))
					{
						CaptureFiles.Edits edits = new CaptureFiles.Edits();
						edits.label = label;
						edits.notes = new ArrayList<>(typed);
						edits.notes.sort((a, b) -> Integer.compare(a.tick, b.tick));
						load(new CaptureFiles.Entry(e.name, e.size, e.modified, e.file, edits, false));
						editMessage.setText("Saved.");
					}
					refreshRecordings();
				});
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("osmm-capture: can't save the notes", ex);
				SwingUtilities.invokeLater(() ->
				{
					editMessage.setForeground(RED);
					editMessage.setText("Couldn't save: " + ex.getMessage());
				});
			}
		});
	}

	private void delete()
	{
		CaptureFiles.Entry e = selected;
		if (e == null || e.recording || e.name.equals(plugin.status().file))
		{
			return;
		}
		int answer = JOptionPane.showOptionDialog(this,
			"Delete " + e.name + " and its notes?\nThis can't be undone.", "Delete recording",
			JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
			new Object[]{"Delete", "Cancel"}, "Cancel");
		if (answer != 0)
		{
			return;
		}
		selected = null;
		applying = true;
		list.clearSelection();
		applying = false;
		showRecording(false);
		run(() ->
		{
			try
			{
				files.delete(e.name);
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("osmm-capture: can't delete", ex);
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
					"Couldn't delete " + e.name + ": " + ex.getMessage(), "Delete recording", JOptionPane.ERROR_MESSAGE));
			}
			SwingUtilities.invokeLater(this::refreshRecordings);
		});
	}

	private void openFolder()
	{
		run(() ->
		{
			try
			{
				Filepath dir = files.dir();
				dir.createDirectories();
				LinkBrowser.open(dir.toString());   // (its own thread; it says so itself if it can't)
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("osmm-capture: can't make the folder", ex);
				SwingUtilities.invokeLater(() ->
				{
					editMessage.setForeground(RED);
					editMessage.setText("Couldn't open the folder: " + ex.getMessage());
				});
			}
		});
	}

	private void run(Runnable r)
	{
		try
		{
			io.execute(r);
		}
		catch (RejectedExecutionException ignored)
		{
			// the plugin is stopping
		}
	}

	/** One note in the editor: its tick, its text and a delete button. */
	private final class NoteRow
	{
		final JPanel panel = new JPanel(new BorderLayout(4, 0));
		final JTextField tickField = new JTextField();
		final JTextField text = new JTextField();

		NoteRow(CaptureFiles.Note n)
		{
			panel.setOpaque(false);
			tickField.setText(Integer.toString(n.tick));
			tickField.setToolTipText("Tick");
			tickField.setPreferredSize(new Dimension(44, tickField.getPreferredSize().height));
			text.setText(n.text);
			text.setToolTipText(n.text.isEmpty() ? null : n.text);
			JButton remove = new JButton(CROSS);
			remove.setToolTipText("Delete note");
			remove.setPreferredSize(new Dimension(22, tickField.getPreferredSize().height));
			remove.addActionListener(e -> removeRow(this));
			panel.add(tickField, BorderLayout.WEST);
			panel.add(text, BorderLayout.CENTER);
			panel.add(remove, BorderLayout.EAST);
		}

		/** The tick typed, or -1 when it isn't a whole number from 0. */
		int tick()
		{
			try
			{
				int t = Integer.parseInt(tickField.getText().trim());
				return t >= 0 ? t : -1;
			}
			catch (NumberFormatException e)
			{
				return -1;
			}
		}
	}

	/** A recording in the list: its label, when it started, and its length and size. */
	private final class EntryRenderer extends JPanel implements ListCellRenderer<CaptureFiles.Entry>
	{
		private final JLabel name = new JLabel();
		private final JLabel when = small("", GREY);
		private final JLabel size = small("", GREY);

		EntryRenderer()
		{
			setLayout(new GridLayout(3, 1));
			setBorder(new EmptyBorder(4, 6, 4, 6));
			name.setFont(FontManager.getRunescapeFont());
			add(name);
			add(when);
			add(size);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends CaptureFiles.Entry> list, CaptureFiles.Entry e,
			int index, boolean isSelected, boolean cellHasFocus)
		{
			String label = e.recording ? plugin.status().label : e.label();
			if (label == null || label.isEmpty())
			{
				name.setText(e.name.endsWith(CaptureFiles.EXTENSION)
					? e.name.substring(0, e.name.length() - CaptureFiles.EXTENSION.length()) : e.name);
				name.setForeground(GREY);
			}
			else
			{
				name.setText(label);
				name.setForeground(WHITE);
			}
			when.setText(CaptureFiles.when(e.start()));
			if (e.recording)
			{
				size.setText("Recording now");
				size.setForeground(RED);
			}
			else
			{
				String length = e.file == null || e.file.ticks < 0 ? "? ticks" : CaptureFiles.ticksText(e.file.ticks);
				size.setText(length + ", " + CaptureFiles.sizeText(e.size));
				size.setForeground(GREY);
			}
			setBackground(isSelected ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			setToolTipText(e.name);
			return this;
		}
	}

	// ---- small helpers ------------------------------------------------------------------------------------

	private static GridBagConstraints row(int y, int top)
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = y;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTH;
		c.insets = new Insets(top, 0, 0, 0);
		return c;
	}

	private static JPanel box()
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(8, 8, 8, 8));
		return p;
	}

	private static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		return l;
	}

	private static JLabel caption(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(WHITE);
		return l;
	}

	private static void setText(JLabel l, String text)
	{
		if (!text.equals(l.getText()))
		{
			l.setText(text);
		}
	}

	private static void setText(JButton b, String text)
	{
		if (!text.equals(b.getText()))
		{
			b.setText(text);
		}
	}

	/** The delete-note icon: a small grey cross. */
	private static BufferedImage cross()
	{
		BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(2, 2, 8, 8);
		g.drawLine(8, 2, 2, 8);
		g.dispose();
		return img;
	}
}
