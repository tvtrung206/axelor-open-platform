/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.mail.service;

import static com.axelor.common.StringUtils.isBlank;

import com.axelor.app.AppSettings;
import com.axelor.app.AvailableAppSettings;
import com.axelor.auth.AuditableRunner;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.common.StringUtils;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.Query;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.mail.ImapAccount;
import com.axelor.mail.MailBuilder;
import com.axelor.mail.MailConstants;
import com.axelor.mail.MailException;
import com.axelor.mail.MailParser;
import com.axelor.mail.MailReader;
import com.axelor.mail.MailSender;
import com.axelor.mail.SmtpAccount;
import com.axelor.mail.db.MailAddress;
import com.axelor.mail.db.MailFollower;
import com.axelor.mail.db.MailMessage;
import com.axelor.mail.db.repo.MailAddressRepository;
import com.axelor.mail.db.repo.MailFollowerRepository;
import com.axelor.mail.db.repo.MailMessageRepository;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.MetaStore;
import com.axelor.meta.db.MetaAttachment;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaAttachmentRepository;
import com.axelor.meta.schema.views.Selection;
import com.axelor.team.db.Team;
import com.axelor.text.GroovyTemplates;
import com.axelor.text.Template;
import com.axelor.text.Templates;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.google.inject.persist.Transactional;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.activation.DataSource;
import javax.inject.Singleton;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.AndTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.SearchTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default {@link MailService} implementation. */
@Singleton
public class MailServiceImpl implements MailService, MailConstants {

  /** The default mail sender is configured from application configuration settings. */
  private MailSender sender;

  /** The default mail reader is configured from application configuration settings. */
  private MailReader reader;

  private boolean senderConfigured;
  private boolean readerConfigured;

  private ExecutorService executor = Executors.newCachedThreadPool();

  private Logger log = LoggerFactory.getLogger(MailService.class);

  private static final Object FETCH_LOCK = new Object();

  public MailServiceImpl() {}

  /**
   * Get {@link MailSender} to use sending the given message.
   *
   * <p>Can be overridden to provide different {@link MailSender} for different messages and object
   * depending on the business requirements.
   *
   * @param message the message to send with the sender
   * @param entity the related entity, can be null if there is no related record
   * @return a {@link MailSender}, null if not configured
   */
  protected MailSender getMailSender(final MailMessage message, Model entity) {
    if (senderConfigured) {
      return sender;
    }
    try {
      return initSender();
    } finally {
      senderConfigured = true;
    }
  }

  private synchronized MailSender initSender() {

    final AppSettings settings = AppSettings.get();

    final String host = settings.get(AvailableAppSettings.MAIL_SMTP_HOST);
    final String port = settings.get(AvailableAppSettings.MAIL_SMTP_PORT);
    final String user = settings.get(AvailableAppSettings.MAIL_SMTP_USER);
    final String pass = settings.get(AvailableAppSettings.MAIL_SMTP_PASSWORD);
    final String channel = settings.get(AvailableAppSettings.MAIL_SMTP_CHANNEL);
    final String from = settings.get(AvailableAppSettings.MAIL_SMTP_FROM);

    final int timeout = settings.getInt(AvailableAppSettings.MAIL_SMTP_TIMEOUT, DEFAULT_TIMEOUT);
    final int connectionTimeout =
        settings.getInt(AvailableAppSettings.MAIL_SMTP_CONNECTION_TIMEOUT, DEFAULT_TIMEOUT);

    final Properties otherProperties = new Properties();
    settings
        .getPropertiesKeysStartingWith(AvailableAppSettings.MAIL_SMTP_PROPERTIES_PREFIX)
        .forEach(
            s -> {
              otherProperties.put(
                  s.substring(AvailableAppSettings.MAIL_SMTP_PROPERTIES_PREFIX.length()),
                  settings.get(s));
            });

    if (isBlank(host)) {
      return null;
    }

    final SmtpAccount smtpAccount = new SmtpAccount(host, port, user, pass, channel, from);
    smtpAccount.setTimeout(timeout);
    smtpAccount.setConnectionTimeout(connectionTimeout);
    smtpAccount.setProperties(otherProperties);
    sender = new MailSender(smtpAccount);

    return sender;
  }

  /**
   * Get {@link MailReader} to use sending the given message.
   *
   * <p>Can be overridden to provide {@link MailReader} configured differently (e.g. from database
   * config).
   *
   * @return a {@link MailSender}, null if not configured
   */
  protected MailReader getMailReader() {
    if (readerConfigured) {
      return reader;
    }
    try {
      return initReader();
    } finally {
      readerConfigured = true;
    }
  }

  private synchronized MailReader initReader() {

    final AppSettings settings = AppSettings.get();

    final String host = settings.get(AvailableAppSettings.MAIL_IMAP_HOST);
    final String port = settings.get(AvailableAppSettings.MAIL_IMAP_PORT);
    final String user = settings.get(AvailableAppSettings.MAIL_IMAP_USER);
    final String pass = settings.get(AvailableAppSettings.MAIL_IMAP_PASSWORD);
    final String channel = settings.get(AvailableAppSettings.MAIL_IMAP_CHANNEL);

    final int timeout = settings.getInt(AvailableAppSettings.MAIL_IMAP_TIMEOUT, DEFAULT_TIMEOUT);
    final int connectionTimeout =
        settings.getInt(AvailableAppSettings.MAIL_IMAP_CONNECTION_TIMEOUT, DEFAULT_TIMEOUT);

    final Properties otherProperties = new Properties();
    settings
        .getPropertiesKeysStartingWith(AvailableAppSettings.MAIL_IMAP_PROPERTIES_PREFIX)
        .forEach(
            s -> {
              otherProperties.put(
                  s.substring(AvailableAppSettings.MAIL_IMAP_PROPERTIES_PREFIX.length()),
                  settings.get(s));
            });

    if (isBlank(host)) {
      return null;
    }

    final ImapAccount account = new ImapAccount(host, port, user, pass, channel);
    account.setTimeout(timeout);
    account.setConnectionTimeout(connectionTimeout);
    account.setProperties(otherProperties);
    reader = new MailReader(account);

    return reader;
  }

  /**
   * Get the subject line for the message.
   *
   * <p>Custom implementation can overrider this method to prepare good subject line.
   *
   * @param message the message for which subject line is required
   * @param entity the related entity, can be null if there is no related record
   * @return subject line
   */
  protected String getSubject(final MailMessage message, Model entity) {
    if (message == null) {
      return null;
    }
    String subject = I18n.get(message.getSubject());
    if (subject != null && entity != null) {
      try {
        subject =
            Mapper.of(entity.getClass()).getNameField().get(entity).toString() + " - " + subject;
      } catch (Exception e) {
      }
    }
    if (subject == null) {
      subject =
          getSubject(message.getParent() == null ? message.getRoot() : message.getParent(), entity);
    }
    // in case of message groups
    if (subject == null && entity instanceof Team) {
      subject = ((Team) entity).getName();
    }
    if (message.getParent() != null && subject != null) {
      subject = "Re: " + subject;
    }
    return subject;
  }

  /**
   * Get the list of recipient email addresses.
   *
   * <p>The default implementation returns email addresses of all followers. Custom implementation
   * may include more per business requirement. For example, including customer email on sale order
   * is confirmed.
   *
   * @param message the message to send
   * @param entity the related entity, can be null if there is no related record
   * @return set of email addresses
   */
  protected Set<String> recipients(final MailMessage message, Model entity) {
    final Set<String> recipients = new LinkedHashSet<>();
    final MailFollowerRepository followers = Beans.get(MailFollowerRepository.class);

    if (message.getRecipients() != null) {
      for (MailAddress address : message.getRecipients()) {
        recipients.add(address.getAddress());
      }
    }
    for (MailFollower follower : followers.findAll(message)) {
      if (Boolean.TRUE.equals(follower.getArchived())) {
        continue;
      }
      if (follower.getEmail() != null) {
        recipients.add(follower.getEmail().getAddress());
      } else if (follower.getUser() != null && follower.getUser().getEmail() != null) {
        recipients.add(follower.getUser().getEmail());
      }
    }

    return Sets.filter(recipients, Predicates.notNull());
  }

  /**
   * Apply a template to prepare message content.
   *
   * <p>The default implementation uses very basic template. Custom implementations can apply
   * different templates depending on message type and related entity or even current customer.
   *
   * @param message the message to send
   * @param entity the related entity, can be null if there is no related record
   * @return final message body text
   * @throws IOException if there is any error applying template
   */
  protected String template(final MailMessage message, Model entity) throws IOException {

    final String text = message.getBody().trim();
    if (text == null
        || !MESSAGE_TYPE_NOTIFICATION.equals(message.getType())
        || !(text.startsWith("{") || text.startsWith("}"))) {
      return text;
    }

    final MailMessageRepository messages = Beans.get(MailMessageRepository.class);
    final Map<String, Object> details = messages.details(message);
    final String jsonBody = details.containsKey("body") ? (String) details.get("body") : text;

    // audit tracking notification is stored as json data
    final ObjectMapper mapper = Beans.get(ObjectMapper.class);
    final Map<String, Object> data =
        mapper.readValue(jsonBody, new TypeReference<Map<String, Object>>() {});

    try {
      updateBody(message, data);
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }

    data.put("entity", entity);

    // TODO: improve template to include messages and tags

    Templates templates = Beans.get(GroovyTemplates.class);
    Template tmpl =
        templates.fromText(
            ""
                + "<ul>"
                + "<% for (def item : tracks) { %>"
                + "<% if (item.containsKey('displayValue')) { %>"
                + "<li><strong>${item.title}</strong>: <span>${item.oldDisplayValue}</span> → <span>${item.displayValue}</span></li>"
                + "<% } else { %>"
                + "<li><strong>${item.title}</strong>: <span>${item.oldValue}</span> → <span>${item.value}</span></li>"
                + "<% } %>"
                + "<% } %>"
                + "</ul>");

    return tmpl.make(data).render();
  }

  /**
   * This adds displayValue and oldDisplayValue to format specific fields values in order to display
   * formatted values in mail
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void updateBody(MailMessage message, Map<String, Object> bodyData) throws Exception {
    if (!MailConstants.MESSAGE_TYPE_NOTIFICATION.equals(message.getType())
        || message.getRelatedModel() == null) {
      return;
    }
    if (message.getBody() == null || message.getBody().trim().charAt(0) != '{') {
      return;
    }
    final Mapper mapper = Mapper.of(Class.forName(message.getRelatedModel()));
    for (Map<String, String> item : (List<Map>) bodyData.get("tracks")) {
      final Property property = mapper.getProperty(item.get("name"));
      if (property == null) {
        continue;
      }
      if (StringUtils.notBlank(property.getSelection())) {
        item.put("displayValue", formatSelection(property, item.get("value")));
        item.put("oldDisplayValue", formatSelection(property, item.get("oldValue")));
        continue;
      }
      if (isBoolean(item.get("value"))) {
        item.put("displayValue", formatBoolean(item.get("value")));
      }
      if (isBoolean(item.get("oldValue"))) {
        item.put("oldDisplayValue", formatBoolean(item.get("oldValue")));
      }
    }
  }

  private boolean isBoolean(String value) {
    return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
  }

  private String formatBoolean(String value) {
    if ("true".equalsIgnoreCase(value)) {
      return I18n.get("True");
    } else if ("false".equalsIgnoreCase(value)) {
      return I18n.get("False");
    } else {
      return null;
    }
  }

  private String formatSelection(Property property, String value) {
    String val = formatSelectionItem(property, value);
    if (val != null) {
      return val;
    }
    if (StringUtils.isEmpty(value) || value.indexOf(",") < 1) {
      return null;
    }
    // try multi-select
    StringJoiner sj = new StringJoiner(", ");
    for (String item : value.split(",")) {
      String itemVal = formatSelectionItem(property, item);
      if (itemVal != null) {
        sj.add(itemVal);
      }
    }
    return sj.length() > 0 ? sj.toString() : null;
  }

  private String formatSelectionItem(Property property, String value) {
    Selection.Option opt = MetaStore.getSelectionItem(property.getSelection(), value);
    return opt == null ? null : opt.getLocalizedTitle();
  }

  /**
   * Find related entity.
   *
   * @param message the message
   * @return related entity or null if there is no related record
   */
  protected final Model findEntity(final MailMessage message) {
    try {
      return (Model)
          JPA.em().find(Class.forName(message.getRelatedModel()), message.getRelatedId());
    } catch (NullPointerException | ClassNotFoundException e) {
      return null;
    }
  }

  @Override
  public void send(final MailMessage message) throws MailException {
    Preconditions.checkNotNull(message, "mail message can't be null");
    final Model related = findEntity(message);
    final MailSender sender = getMailSender(message, related);
    if (sender == null) {
      return;
    }

    final Set<String> recipients = recipients(message, related);
    if (recipients.isEmpty()) {
      return;
    }

    final MailMessageRepository messages = Beans.get(MailMessageRepository.class);
    final MailBuilder builder = sender.compose().subject(getSubject(message, related));

    for (String recipient : recipients) {
      builder.to(recipient);
    }

    for (MetaAttachment attachment : messages.findAttachments(message)) {
      final Path filePath = MetaFiles.getPath(attachment.getMetaFile());
      final File file = filePath.toFile();
      builder.attach(file.getName(), file.toString());
    }

    final MimeMessage email;
    try {
      builder.html(template(message, related));
      email = builder.build(message.getMessageId());
      final Set<String> references = new LinkedHashSet<>();
      if (message.getParent() != null) {
        references.add(message.getParent().getMessageId());
      }
      if (message.getRoot() != null) {
        references.add(message.getRoot().getMessageId());
      }
      if (!references.isEmpty()) {
        email.setHeader("References", Joiner.on(" ").skipNulls().join(references));
      }
    } catch (MessagingException | IOException e) {
      throw new MailException(e);
    }

    // send email using a separate process to void thread blocking
    executor.submit(
        new Callable<Boolean>() {
          @Override
          public Boolean call() throws Exception {
            send(sender, email);
            return true;
          }
        });
  }

  @Transactional(rollbackOn = Exception.class)
  protected void send(final MailSender sender, final MimeMessage email) throws Exception {
    final AuditableRunner runner = Beans.get(AuditableRunner.class);
    final Callable<Boolean> job =
        new Callable<Boolean>() {
          @Override
          public Boolean call() throws Exception {
            sender.send(email);
            messageSent(email);
            return true;
          }
        };
    runner.run(job);
  }

  /**
   * This method is called when email is sent successfully.
   *
   * <p>This method is called by {@link #send(MailSender, MimeMessage)} which is running under a
   * transaction with super user access.
   *
   * @param email the email message sent
   */
  protected void messageSent(final MimeMessage email) {}

  /**
   * This method is called when a new email message received.
   *
   * <p>This method is called by {@link #fetch(MailReader)} which is running under a database
   * transaction. If this method returns an instance of {@link MailMessage}, the {@link
   * #fetch(MailReader)} will persist the record.
   *
   * <p>The default implementation will parse the message and check if it's a reply to a message
   * sent by the system.
   *
   * @param email the incoming email message
   * @return an instance of {@link MailMessage} to save in database or null to ignore
   * @throws MessagingException if there is any error accessing the given message
   * @throws IOException if there is any error accessing message content
   */
  protected MailMessage messageReceived(MimeMessage email) throws MessagingException, IOException {

    log.info("new email recieved: {}", email.getMessageID());

    final MailParser parser = new MailParser(email).parse();
    final String messageId = email.getMessageID();

    final Set<String> parentIds = new HashSet<>();
    String parentId;

    parentId = parser.getHeader("In-Reply-To");
    if (parentId != null) {
      parentIds.add(parentId);
    }
    parentId = parser.getHeader("X-References");
    if (parentId != null) {
      parentIds.addAll(Splitter.on(" ").trimResults().omitEmptyStrings().splitToList(parentId));
    }

    parentId = parser.getHeader("References");
    if (parentId != null) {
      parentIds.addAll(Splitter.on(" ").trimResults().omitEmptyStrings().splitToList(parentId));
    }

    // default implementation only supports reply
    if (parentIds.isEmpty()) {
      log.info("it's not a reply, ignoring...");
      return null;
    }

    final UserRepository users = Beans.get(UserRepository.class);
    final MailAddressRepository emails = Beans.get(MailAddressRepository.class);
    final MailMessageRepository messages = Beans.get(MailMessageRepository.class);

    final MailMessage parent =
        messages.all().filter("self.messageId in (:ids)").bind("ids", parentIds).fetchOne();

    // no parent message found, ignore
    if (parent == null) {
      log.info("parent message doesn't exist, ignoring...");
      return null;
    }

    final MailMessage existing = messages.all().filter("self.messageId = ?", messageId).fetchOne();

    // very unlikely, message already exist
    if (existing != null) {
      log.info("message already imported, ignoring...");
      return null;
    }

    final MailMessage message = new MailMessage();
    final String content = parser.getHtml() == null ? parser.getText() : parser.getHtml();
    final String summary = parser.getSummary();

    message.setSubject(parser.getSubject());
    message.setBody(content);
    message.setSummary(summary);
    message.setType(MESSAGE_TYPE_EMAIL);
    message.setRelatedModel(parent.getRelatedModel());
    message.setRelatedId(parent.getRelatedId());
    message.setRelatedName(parent.getRelatedName());

    final InternetAddress from = parser.getFrom();
    final MailAddress address = emails.findOrCreate(from.getAddress(), from.getPersonal());
    final User author = users.findByEmail(from.getAddress());

    message.setAuthor(author);
    message.setFrom(address);

    // need to save before attaching files
    messages.save(message);

    log.info("message from: {}", from.getAddress());

    // handle attachments
    final MetaAttachmentRepository attachments = Beans.get(MetaAttachmentRepository.class);
    final MetaFiles files = Beans.get(MetaFiles.class);
    for (DataSource ds : parser.getAttachments()) {
      log.info("attaching file: {}", ds.getName());
      final MetaFile file = files.upload(ds.getInputStream(), ds.getName());
      final MetaAttachment attachment = files.attach(file, message);
      attachments.save(attachment);
    }

    return message;
  }

  /**
   * Fetch email messages from the given mail store.
   *
   * @param reader the mail reader to fetch message from
   * @throws MessagingException if unable to parse message
   * @throws IOException if unable to load message content
   */
  @Transactional(rollbackOn = Exception.class)
  protected void fetch(final MailReader reader) throws MessagingException, IOException {

    final Store store = reader.getStore();
    final Folder inbox = store.getFolder("INBOX");
    final MailMessageRepository repo = Beans.get(MailMessageRepository.class);

    log.debug("Fetching new emails from: {}", store.getURLName());

    // open as READ_WRITE to mark messages as seen
    inbox.open(Folder.READ_WRITE);

    // find all unseen messages
    final FlagTerm unseen = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
    final FlagTerm flagged = new FlagTerm(new Flags("fetched"), false);
    final SearchTerm term = new AndTerm(unseen, flagged);

    final FetchProfile profile = new FetchProfile();
    final Message[] messages = inbox.search(term);

    profile.add(FetchProfile.Item.ENVELOPE);

    // actually fetch the messages
    inbox.fetch(messages, profile);

    int count = 0;
    for (Message message : messages) {
      if (message instanceof MimeMessage) {
        final MailMessage entity = messageReceived((MimeMessage) message);
        if (entity != null) {
          repo.save(entity);
          count += 1;
        } else {
          message.setFlag(Flags.Flag.SEEN, false);
          message.setFlags(new Flags("fetched"), true);
        }
      }
    }

    log.debug("Fetched {} emails from: {}", count, store.getURLName());
    inbox.close(false);
  }

  @Override
  public void fetch() throws MailException {
    synchronized (FETCH_LOCK) {
      final MailReader reader = getMailReader();
      if (reader == null) {
        return;
      }
      final AuditableRunner runner = Beans.get(AuditableRunner.class);
      runner.run(
          () -> {
            try {
              fetch(reader);
            } catch (Exception e) {
              log.error("Unable to fetch messages", e);
            }
          });
    }
  }

  @Override
  public Model resolve(String email) {
    final UserRepository users = Beans.get(UserRepository.class);
    return users.all().filter("self.email is not null and self.email = ?", email).fetchOne();
  }

  @Override
  public List<InternetAddress> findEmails(String matching, List<String> selected, int maxResult) {

    final List<String> where = new ArrayList<>();
    final Map<String, Object> params = new HashMap<>();

    where.add("self.email is not null");

    if (!isBlank(matching)) {
      where.add("(LOWER(self.email) like LOWER(:email) OR LOWER(self.name) like LOWER(:email))");
      params.put("email", "%" + matching + "%");
    }
    if (selected != null && !selected.isEmpty()) {
      where.add("self.email not in (:selected)");
      params.put("selected", selected);
    }

    final String filter = Joiner.on(" AND ").join(where);
    final Query<User> q = Query.of(User.class);

    if (!isBlank(filter)) {
      q.filter(filter);
      q.bind(params);
    }

    final List<InternetAddress> addresses = new ArrayList<>();

    for (User user : q.fetch(maxResult)) {
      try {
        final InternetAddress item = new InternetAddress(user.getEmail(), user.getName());
        addresses.add(item);
      } catch (UnsupportedEncodingException e) {
      }
    }

    return addresses;
  }
}
