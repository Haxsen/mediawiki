/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.filter.mediawiki.xml.internal.input;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.mediawiki.syntax.MediaWikiSyntaxInputProperties;
import org.xwiki.contrib.mediawiki.syntax.MediaWikiSyntaxInputProperties.ReferenceType;
import org.xwiki.contrib.mediawiki.syntax.internal.parser.MediaWikiStreamParser;
import org.xwiki.filter.input.BeanInputFilterStream;
import org.xwiki.filter.input.BeanInputFilterStreamFactory;
import org.xwiki.filter.input.InputFilterStreamFactory;
import org.xwiki.filter.input.StringInputSource;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.listener.Listener;
import org.xwiki.rendering.listener.VoidListener;
import org.xwiki.rendering.listener.WrappingListener;
import org.xwiki.rendering.listener.reference.AttachmentResourceReference;
import org.xwiki.rendering.listener.reference.DocumentResourceReference;
import org.xwiki.rendering.listener.reference.ResourceReference;
import org.xwiki.rendering.renderer.PrintRenderer;
import org.xwiki.rendering.renderer.PrintRendererFactory;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.transformation.RenderingContext;

/**
 * Modify on the fly various events (link reference, macros, etc).
 * 
 * @version $Id$
 */
@Component(roles = MediaWikiContextConverterListener.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class MediaWikiContextConverterListener extends WrappingListener
{
    @Inject
    private FileCatcherListener fileCatcher;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    @Named(MediaWikiSyntaxInputProperties.FILTER_STREAM_TYPE_STRING)
    private InputFilterStreamFactory parserFactory;

    @Inject
    private RenderingContext renderingContext;

    @Inject
    private ComponentManager componentManager;

    private MediaWikiInputFilterStream stream;

    private Deque<ResourceReference> currentReference = new LinkedList<>();

    private Syntax targetSyntax;

    void initialize(Listener listener, MediaWikiInputFilterStream stream, Syntax targetSyntax)
    {
        setWrappedListener(listener);

        this.fileCatcher.setWrappedListener(new VoidListener());
        this.stream = stream;
        this.targetSyntax = targetSyntax;
    }

    /**
     * @return the catched files
     */
    public Set<String> getFiles()
    {
        return this.fileCatcher.getFiles();
    }

    private String compact(EntityReference entityReference)
    {
        if (Objects.equals(this.stream.currentParentReference, entityReference.getParent())) {
            return entityReference.getName();
        } else {
            return this.serializer.serialize(entityReference);
        }
    }

    private DocumentResourceReference refactor(DocumentResourceReference reference)
    {
        DocumentResourceReference newReference = reference;

        EntityReference entityReference = this.stream.toEntityReference(reference.getReference());
        if (entityReference != null) {
            newReference = new DocumentResourceReference(compact(entityReference));
            newReference.setParameters(reference.getParameters());
            newReference.setAnchor(reference.getAnchor());
            newReference.setQueryString(reference.getQueryString());
        }

        return newReference;
    }

    private AttachmentResourceReference refactor(AttachmentResourceReference reference)
    {
        AttachmentResourceReference newReference = reference;

        // Refactor the reference to fit XWiki environment
        EntityReference entityReference = this.stream.toFileEntityReference(reference.getReference());

        if (entityReference != null) {
            entityReference = new EntityReference(reference.getReference(), EntityType.ATTACHMENT, entityReference);
            newReference = new AttachmentResourceReference(this.serializer.serialize(entityReference));
            newReference.setParameters(reference.getParameters());
            newReference.setAnchor(reference.getAnchor());
            newReference.setQueryString(reference.getQueryString());
        }

        return newReference;
    }

    @Override
    public void beginLink(ResourceReference reference, boolean isFreeStandingURI, Map<String, String> parameters)
    {
        // Remember files
        this.fileCatcher.beginLink(reference, isFreeStandingURI, parameters);

        ResourceReference newReference = reference;

        // Refactor the reference if needed
        if (reference instanceof AttachmentResourceReference) {
            newReference = refactor((AttachmentResourceReference) reference);
        } else if (reference instanceof DocumentResourceReference) {
            newReference = refactor((DocumentResourceReference) reference);
        }

        this.currentReference.push(newReference);

        super.beginLink(newReference, isFreeStandingURI, parameters);
    }

    @Override
    public void endLink(ResourceReference reference, boolean isFreeStandingURI, Map<String, String> parameters)
    {
        super.endLink(this.currentReference.pop(), isFreeStandingURI, parameters);
    }

    @Override
    public void onImage(ResourceReference reference, boolean isFreeStandingURI, Map<String, String> parameters)
    {
        // Remember files
        this.fileCatcher.onImage(reference, isFreeStandingURI, parameters);

        ResourceReference newReference = reference;

        // Refactor the reference if needed
        if (reference instanceof AttachmentResourceReference) {
            newReference = refactor((AttachmentResourceReference) reference);
        }

        super.onImage(newReference, isFreeStandingURI, parameters);
    }

    @Override
    public void onMacro(String id, Map<String, String> parameters, String content, boolean isInline)
    {
        // Remember files
        this.fileCatcher.onMacro(id, parameters, content, isInline);

        // Convert macros containing wiki content
        // TODO: make it configurable
        String convertedContent = content;
        if (id.equals("gallery") || id.equals("blockquote")) {
            convertedContent = convertWikiContent(convertedContent);
        }

        super.onMacro(id, parameters, convertedContent, isInline);
    }

    private String convertWikiContent(String content)
    {
        String convertedContent = content;

        PrintRenderer renderer = getRenderer();
        if (renderer != null) {
            MediaWikiSyntaxInputProperties parserProperties = new MediaWikiSyntaxInputProperties();
            parserProperties.setSource(new StringInputSource(content));
            // Make sure to keep source references unchanged
            parserProperties.setReferenceType(ReferenceType.MEDIAWIKI);

            Listener currentListener = getWrappedListener();

            // Generate events
            try (BeanInputFilterStream<MediaWikiSyntaxInputProperties> stream =
                ((BeanInputFilterStreamFactory) this.parserFactory).createInputFilterStream(parserProperties)) {
                setWrappedListener(renderer);

                stream.read(this);

                convertedContent = renderer.getPrinter().toString();
            } catch (Exception e) {
                // TODO log something ?
            } finally {
                setWrappedListener(currentListener);
            }
        }

        return convertedContent;
    }

    private PrintRenderer getRenderer()
    {
        Syntax syntax = this.targetSyntax;

        if (this.targetSyntax == null) {
            syntax = this.renderingContext.getTargetSyntax();
        }

        if (syntax != null && !syntax.equals(MediaWikiStreamParser.SYNTAX)) {
            try {
                PrintRendererFactory factory =
                    this.componentManager.getInstance(PrintRendererFactory.class, syntax.toIdString());
                return factory.createRenderer(new DefaultWikiPrinter());
            } catch (ComponentLookupException e) {
                return null;
            }
        }

        return null;
    }
}