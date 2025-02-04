/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.engine.impl.bpmn.behavior;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.activation.DataSource;
import javax.naming.NamingException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;
import org.apache.commons.mail.MultiPartEmail;
import org.apache.commons.mail.SimpleEmail;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.common.engine.impl.cfg.mail.MailServerInfo;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.content.api.ContentItem;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.helper.SkipExpressionUtil;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * @author Joram Barrez
 * @author Frederik Heremans
 * @author Tim Stephenson
 * @author Filip Hrisafov
 */
public class MailActivityBehavior extends AbstractBpmnActivityBehavior {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(MailActivityBehavior.class);

    private static final String NEWLINE_REGEX = "\\r?\\n";

    protected Expression to;
    protected Expression from;
    protected Expression cc;
    protected Expression bcc;
    protected Expression headers;
    protected Expression subject;
    protected Expression text;
    protected Expression textVar;
    protected Expression html;
    protected Expression htmlVar;
    protected Expression charset;
    protected Expression ignoreException;
    protected Expression exceptionVariableName;
    protected Expression attachments;

    @Override
    public void execute(DelegateExecution execution) {
        FlowElement flowElement = execution.getCurrentFlowElement();
        boolean isSkipExpressionEnabled = false;
        String skipExpressionText = null;
        CommandContext commandContext = CommandContextUtil.getCommandContext();
        if (flowElement instanceof ServiceTask) {
            ServiceTask serviceTask = (ServiceTask) flowElement;
            skipExpressionText = serviceTask.getSkipExpression();
            isSkipExpressionEnabled = SkipExpressionUtil.isSkipExpressionEnabled(skipExpressionText, flowElement.getId(), execution, commandContext);
        }

        if (!isSkipExpressionEnabled || !SkipExpressionUtil.shouldSkipFlowElement(skipExpressionText, flowElement.getId(), execution, commandContext)) {
            boolean doIgnoreException = Boolean.parseBoolean(getStringFromField(ignoreException, execution));
            String exceptionVariable = getStringFromField(exceptionVariableName, execution);
            Email email = null;
            try {
                String headersStr = getStringFromField(headers, execution);
                Collection<String> toList = parseRecipients(to, execution);
                String fromStr = getStringFromField(from, execution);
                Collection<String> ccList = parseRecipients(cc, execution);
                Collection<String> bccList = parseRecipients(bcc, execution);
                String subjectStr = getStringFromField(subject, execution);
                String textStr = textVar == null ? getStringFromField(text, execution) : getStringFromField(getExpression(execution, textVar), execution);
                String htmlStr = htmlVar == null ? getStringFromField(html, execution) : getStringFromField(getExpression(execution, htmlVar), execution);
                String charSetStr = getStringFromField(charset, execution);
                List<File> files = new LinkedList<>();
                List<DataSource> dataSources = new LinkedList<>();
                getFilesFromFields(attachments, execution, files, dataSources);

                if (toList.isEmpty() && ccList.isEmpty() && bccList.isEmpty()) {
                    throw new FlowableException("No recipient could be found for sending email");
                }

                email = createEmail(textStr, htmlStr, attachmentsExist(files, dataSources));
                addHeader(email, headersStr);
                addTo(email, toList, execution.getTenantId());
                setFrom(email, fromStr, execution.getTenantId());
                addCc(email, ccList, execution.getTenantId());
                addBcc(email, bccList, execution.getTenantId());
                setSubject(email, subjectStr);
                setMailServerProperties(email, execution.getTenantId());
                setCharset(email, charSetStr, execution.getTenantId());
                attach(email, files, dataSources);

                email.send();

            } catch (FlowableException e) {
                handleException(execution, e.getMessage(), e, doIgnoreException, exceptionVariable);
            } catch (EmailException e) {
                handleException(execution, "Could not send e-mail in execution " + execution.getId(), e, doIgnoreException, exceptionVariable);
            }
        }

        leave(execution);
    }

    protected void addHeader(Email email, String headersStr) {
        if (headersStr == null) {
            return;
        }
        for (String headerEntry : headersStr.split(NEWLINE_REGEX)) {
            String[] split = headerEntry.split(":");
            if (split.length != 2) {
                throw new FlowableIllegalArgumentException("When using email headers name and value must be defined colon separated. (e.g. X-Attribute: value");
            }
            String name = split[0].trim();
            String value = split[1].trim();
            email.addHeader(name, value);
        }
    }

    private boolean attachmentsExist(List<File> files, List<DataSource> dataSources) {
        return !((files == null || files.isEmpty()) && (dataSources == null || dataSources.isEmpty()));
    }

    protected Email createEmail(String text, String html, boolean attachmentsExist) {
        if (html != null) {
            return createHtmlEmail(text, html);
        } else if (text != null) {
            if (!attachmentsExist) {
                return createTextOnlyEmail(text);
            } else {
                return createMultiPartEmail(text);
            }
        } else {
            throw new FlowableIllegalArgumentException("'html' or 'text' is required to be defined when using the mail activity");
        }
    }

    protected HtmlEmail createHtmlEmail(String text, String html) {
        HtmlEmail email = new HtmlEmail();
        try {
            email.setHtmlMsg(html);
            if (text != null) { // for email clients that don't support html
                email.setTextMsg(text);
            }
            return email;
        } catch (EmailException e) {
            throw new FlowableException("Could not create HTML email", e);
        }
    }

    protected SimpleEmail createTextOnlyEmail(String text) {
        SimpleEmail email = new SimpleEmail();
        try {
            email.setMsg(text);
            return email;
        } catch (EmailException e) {
            throw new FlowableException("Could not create text-only email", e);
        }
    }

    protected MultiPartEmail createMultiPartEmail(String text) {
        MultiPartEmail email = new MultiPartEmail();
        try {
            email.setMsg(text);
            return email;
        } catch (EmailException e) {
            throw new FlowableException("Could not create text-only email", e);
        }
    }

    protected void addTo(Email email, Collection<String> to, String tenantId) {
        if (to == null || to.isEmpty()) {
            return;
        }
        Collection<String> newTo = to;
        Collection<String> forceTo = getForceTo(tenantId);
        if (forceTo != null && !forceTo.isEmpty()) {
            newTo = forceTo;
        }
        if (!newTo.isEmpty()) {
            for (String t : newTo) {
                try {
                    email.addTo(t);
                } catch (EmailException e) {
                    throw new FlowableException("Could not add " + t + " as recipient", e);
                }
            }
        } else {
            throw new FlowableException("No recipient could be found for sending email");
        }
    }

    protected void setFrom(Email email, String from, String tenantId) {
        String fromAddress = null;

        if (from != null) {
            fromAddress = from;
        } else { // use default configured from address in process engine config
            if (tenantId != null && tenantId.length() > 0) {
                Map<String, MailServerInfo> mailServers = CommandContextUtil.getProcessEngineConfiguration().getMailServers();
                if (mailServers != null && mailServers.containsKey(tenantId)) {
                    MailServerInfo mailServerInfo = mailServers.get(tenantId);
                    fromAddress = mailServerInfo.getMailServerDefaultFrom();
                }
            }

            if (fromAddress == null) {
                fromAddress = CommandContextUtil.getProcessEngineConfiguration().getMailServerDefaultFrom();
            }
        }

        try {
            email.setFrom(fromAddress);
        } catch (EmailException e) {
            throw new FlowableException("Could not set " + from + " as from address in email", e);
        }
    }

    protected void addCc(Email email, Collection<String> cc, String tenantId) {
        if (cc == null || cc.isEmpty()) {
            return;
        }
        Collection<String> newCc = cc;

        Collection<String> forceTo = getForceTo(tenantId);
        if (forceTo != null && !forceTo.isEmpty()) {
            newCc = forceTo;
        }
        if (!newCc.isEmpty()) {
            for (String c : newCc) {
                try {
                    email.addCc(c);
                } catch (EmailException e) {
                    throw new FlowableException("Could not add " + c + " as cc recipient", e);
                }
            }
        }
    }

    protected void addBcc(Email email, Collection<String> bcc, String tenantId) {
        if (bcc == null || bcc.isEmpty()) {
            return;
        }
        Collection<String> newBcc = bcc;
        Collection<String> forceTo = getForceTo(tenantId);
        if (forceTo != null && !forceTo.isEmpty()) {
            newBcc = forceTo;
        }
        if (!newBcc.isEmpty()) {
            for (String b : newBcc) {
                try {
                    email.addBcc(b);
                } catch (EmailException e) {
                    throw new FlowableException("Could not add " + b + " as bcc recipient", e);
                }
            }
        }
    }

    protected void attach(Email email, List<File> files, List<DataSource> dataSources) throws EmailException {
        if (!(email instanceof MultiPartEmail && attachmentsExist(files, dataSources))) {
            return;
        }
        MultiPartEmail mpEmail = (MultiPartEmail) email;
        for (File file : files) {
            mpEmail.attach(file);
        }
        for (DataSource ds : dataSources) {
            if (ds != null) {
                mpEmail.attach(ds, ds.getName(), null);
            }
        }
    }

    protected void setSubject(Email email, String subject) {
        email.setSubject(subject != null ? subject : "");
    }

    protected void setMailServerProperties(Email email, String tenantId) {
        ProcessEngineConfigurationImpl processEngineConfiguration = CommandContextUtil.getProcessEngineConfiguration();

        boolean isMailServerSet = false;
        if (tenantId != null && tenantId.length() > 0) {
            if (processEngineConfiguration.getMailSessionJndi(tenantId) != null) {
                setEmailSession(email, processEngineConfiguration.getMailSessionJndi(tenantId));
                isMailServerSet = true;

            } else if (processEngineConfiguration.getMailServer(tenantId) != null) {
                MailServerInfo mailServerInfo = processEngineConfiguration.getMailServer(tenantId);
                String host = mailServerInfo.getMailServerHost();
                if (host == null) {
                    throw new FlowableException("Could not send email: no SMTP host is configured for tenantId " + tenantId);
                }
                email.setHostName(host);

                email.setSmtpPort(mailServerInfo.getMailServerPort());
                email.setSslSmtpPort(Integer.toString(mailServerInfo.getMailServerSSLPort()));

                email.setSSLOnConnect(mailServerInfo.isMailServerUseSSL());
                email.setStartTLSEnabled(mailServerInfo.isMailServerUseTLS());

                String user = mailServerInfo.getMailServerUsername();
                String password = mailServerInfo.getMailServerPassword();
                if (user != null && password != null) {
                    email.setAuthentication(user, password);
                }

                isMailServerSet = true;
            }
        }

        if (!isMailServerSet) {
            String mailSessionJndi = processEngineConfiguration.getMailSessionJndi();
            if (mailSessionJndi != null) {
                setEmailSession(email, mailSessionJndi);

            } else {
                String host = processEngineConfiguration.getMailServerHost();
                if (host == null) {
                    throw new FlowableException("Could not send email: no SMTP host is configured");
                }
                email.setHostName(host);

                int port = processEngineConfiguration.getMailServerPort();
                email.setSmtpPort(port);
                email.setSslSmtpPort(Integer.toString(processEngineConfiguration.getMailServerSSLPort()));

                email.setSSLOnConnect(processEngineConfiguration.getMailServerUseSSL());
                email.setStartTLSEnabled(processEngineConfiguration.getMailServerUseTLS());

                String user = processEngineConfiguration.getMailServerUsername();
                String password = processEngineConfiguration.getMailServerPassword();
                if (user != null && password != null) {
                    email.setAuthentication(user, password);
                }
            }
        }
    }

    protected void setEmailSession(Email email, String mailSessionJndi) {
        try {
            email.setMailSessionFromJNDI(mailSessionJndi);
        } catch (NamingException e) {
            throw new FlowableException("Could not send email: Incorrect JNDI configuration", e);
        }
    }

    protected void setCharset(Email email, String charSetStr, String tenantId) {
        if (charset != null) {
            email.setCharset(charSetStr);
        } else {
            Charset mailServerDefaultCharset = getDefaultCharSet(tenantId);
            if (mailServerDefaultCharset != null) {
                email.setCharset(mailServerDefaultCharset.name());
            }
        }
    }

    protected Collection<String> splitAndTrim(String str) {
        if (str != null) {
            return Arrays.stream(str.split(",")).map(String::trim).collect(Collectors.toList());
        }
        return null;
    }

    protected String getStringFromField(Expression expression, DelegateExecution execution) {
        if (expression != null) {
            Object value = expression.getValue(execution);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    protected Collection<String> parseRecipients(Expression expression, DelegateExecution execution) {
        if (expression == null) {
            return Collections.emptyList();
        }
        Object value = expression.getValue(execution);
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection) {
            return (Collection<String>) value;
        } else if (value instanceof ArrayNode) {
            ArrayNode arrayNode = (ArrayNode) value;
            Collection<String> recipients = new ArrayList<>(arrayNode.size());
            for (JsonNode node : arrayNode) {
                recipients.add(node.asText());
            }
            return recipients;
        } else {
            String str = value.toString();
            if (StringUtils.isNotEmpty(str)) {
                return Arrays.asList(value.toString().split("[\\s]*,[\\s]*"));
            }
        }
        return Collections.emptyList();
    }

    protected void getFilesFromFields(Expression expression, DelegateExecution execution, List<File> files, List<DataSource> dataSources) {

        if (expression == null) {
            return;
        }

        Object value = expression.getValue(execution);
        if (value != null) {

            if (value instanceof Collection) {
                Collection collection = (Collection) value;
                if (!collection.isEmpty()) {
                    for (Object object : collection) {
                        addExpressionValueToAttachments(object, files, dataSources);
                    }
                }

            } else {
                addExpressionValueToAttachments(value, files, dataSources);

            }

            files.removeIf(file -> !fileExists(file));
        }
    }

    protected void addExpressionValueToAttachments(Object value, List<File> files, List<DataSource> dataSources) {
        if (value instanceof File) {
            files.add((File) value);

        } else if (value instanceof String) {
            files.add(new File((String) value));

        } else if (value instanceof File[]) {
            Collections.addAll(files, (File[]) value);

        } else if (value instanceof String[]) {
            String[] paths = (String[]) value;
            for (String path : paths) {
                files.add(new File(path));
            }

        } else if (value instanceof DataSource) {
            dataSources.add((DataSource) value);

        } else if (value instanceof DataSource[]) {
            for (DataSource ds : (DataSource[]) value) {
                if (ds != null) {
                    dataSources.add(ds);
                }
            }

        } else if (value instanceof ContentItem) {
            dataSources.add(new ContentItemDataSourceWrapper((ContentItem) value));

        } else if (value instanceof ContentItem[]) {
            for (ContentItem contentItem : (ContentItem[]) value) {
                dataSources.add(new ContentItemDataSourceWrapper(contentItem));
            }

        } else {
            throw new FlowableException("Invalid attachment type: " + value.getClass());

        }
    }

    protected boolean fileExists(File file) {
        return file != null && file.exists() && file.isFile() && file.canRead();
    }

    protected Expression getExpression(DelegateExecution execution, Expression var) {
        String variable = (String) execution.getVariable(var.getExpressionText());
        return CommandContextUtil.getProcessEngineConfiguration().getExpressionManager().createExpression(variable);
    }

    protected void handleException(DelegateExecution execution, String msg, Exception e, boolean doIgnoreException, String exceptionVariable) {
        if (doIgnoreException) {
            LOGGER.info("Ignoring email send error: {}", msg, e);
            if (exceptionVariable != null && exceptionVariable.length() > 0) {
                execution.setVariable(exceptionVariable, msg);
            }
        } else {
            if (e instanceof FlowableException) {
                throw (FlowableException) e;
            } else {
                throw new FlowableException(msg, e);
            }
        }
    }

    protected Collection<String> getForceTo(String tenantId) {
        String forceTo = null;
        if (tenantId != null && tenantId.length() > 0) {
            Map<String, MailServerInfo> mailServers = CommandContextUtil.getProcessEngineConfiguration().getMailServers();
            if (mailServers != null && mailServers.containsKey(tenantId)) {
                MailServerInfo mailServerInfo = mailServers.get(tenantId);
                forceTo = mailServerInfo.getMailServerForceTo();
            }
        }

        if (forceTo == null) {
            forceTo = CommandContextUtil.getProcessEngineConfiguration().getMailServerForceTo();
        }

        return splitAndTrim(forceTo);
    }

    protected Charset getDefaultCharSet(String tenantId) {
        Charset defaultCharset = null;
        if (StringUtils.isNotBlank(tenantId)) {
            MailServerInfo mailServerInfo = CommandContextUtil.getProcessEngineConfiguration().getMailServer(tenantId);
            if (mailServerInfo != null) {
                defaultCharset = mailServerInfo.getMailServerDefaultCharset();
            }

        }

        if (defaultCharset == null) {
            defaultCharset = CommandContextUtil.getProcessEngineConfiguration().getMailServerDefaultCharset();
        }

        return defaultCharset;
    }

    public static class ContentItemDataSourceWrapper implements DataSource {

        protected ContentItem contentItem;

        public ContentItemDataSourceWrapper(ContentItem contentItem) {
            this.contentItem = contentItem;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return CommandContextUtil.getContentService().getContentItemData(contentItem.getId());
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            // Not needed for mail attachment
            return null;
        }

        @Override
        public String getContentType() {
            return contentItem.getMimeType();
        }

        @Override
        public String getName() {
            return contentItem.getName();
        }

    }

}
