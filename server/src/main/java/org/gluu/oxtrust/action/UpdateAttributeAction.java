/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.action;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import lombok.Data;

import org.gluu.oxtrust.ldap.service.AttributeService;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.security.Restrict;
import org.jboss.seam.faces.FacesMessages;
import org.jboss.seam.international.StatusMessage.Severity;
import org.jboss.seam.log.Log;
import org.xdi.config.oxtrust.ApplicationConfiguration;
import org.xdi.ldap.model.GluuStatus;
import org.xdi.model.GluuAttribute;
import org.xdi.model.GluuUserRole;
import org.xdi.model.SchemaEntry;
import org.xdi.service.SchemaService;
import org.xdi.util.StringHelper;

/**
 * Action class for updating attribute metadata
 * 
 * @author Yuriy Movchan Date: 10.19.2010
 */
@Scope(ScopeType.CONVERSATION)
@Name("updateAttributeAction")
@Restrict("#{identity.loggedIn}")
public @Data class UpdateAttributeAction implements Serializable {

	private static final long serialVersionUID = -2932167044333943687L;

	@Logger
	private Log log;

	@In
	private AttributeService attributeService;

	@In
	private SchemaService schemaService;

	@In
	private FacesMessages facesMessages;
	
	@In(value = "#{oxTrustConfiguration.applicationConfiguration}")
	private ApplicationConfiguration applicationConfiguration;

	private String inum;
	private GluuAttribute attribute;
	private boolean update;
	private boolean showAttributeDeleteConfirmation;
	private boolean showAttributeExistConfirmation;
	
	private boolean validationToggle;
	private boolean tooltipToggle;

	private boolean canEdit;

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public String add() {
		if (this.attribute != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		this.update = false;

		this.showAttributeDeleteConfirmation = false;
		this.showAttributeExistConfirmation = false;

		this.attribute = new GluuAttribute();
		this.attribute.setStatus(GluuStatus.ACTIVE);
		this.attribute.setEditType(new GluuUserRole[] { GluuUserRole.ADMIN });
		this.attribute.setOrigin(attributeService.getCustomOrigin());

		this.canEdit = true;

		return OxTrustConstants.RESULT_SUCCESS;
	}

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public String update() {
		if (this.attribute != null) {
			return OxTrustConstants.RESULT_SUCCESS;
		}

		this.update = true;

		this.showAttributeDeleteConfirmation = false;
		this.showAttributeExistConfirmation = false;

		if (!loadAttribute(this.inum)) {
			return OxTrustConstants.RESULT_FAILURE;
		}
		
		if(attribute.getRegExp() != null){
			validationToggle = true;
		}
		
		if(attribute.getGluuTooltip()  != null){
			tooltipToggle = true;
		}
		return OxTrustConstants.RESULT_SUCCESS;
	}

	private boolean loadAttribute(String inum) {
		try {
			this.attribute = attributeService.getAttributeByInum(inum);
		} catch (LdapMappingException ex) {
			log.error("Failed to find attribute {0}", ex, inum);
		}

		if (this.attribute == null) {
			return false;
		}

		initAttribute();

		this.canEdit = isAllowEdit();

		return true;
	}

	private boolean isAllowEdit() {
		if (StringHelper.equalsIgnoreCase(attribute.getOrigin(), applicationConfiguration.getPersonCustomObjectClass())) {
			return true;
		}

		return this.attribute.isAdminCanEdit();
	}

	private void initAttribute() {
		if (StringHelper.isEmpty(this.attribute.getSaml1Uri())) {
			String namespace;
			if (attribute.isCustom() || StringHelper.isEmpty(attribute.getUrn())
					&& attribute.getUrn().startsWith("urn:gluu:dir:attribute-def:")) {
				namespace = "gluu";
			} else {
				namespace = "mace";
			}
			this.attribute.setSaml1Uri(String.format("urn:%s:dir:attribute-def:%s", namespace, attribute.getName()));
		}

		if (StringHelper.isEmpty(this.attribute.getSaml2Uri())) {
			this.attribute.setSaml2Uri(attributeService.getDefaultSaml2Uri(attribute.getName()));
		}
	}

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public void cancel() {
	}

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public String save() {
		return save(true);
	}

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public String save(boolean addToSchema) {
		boolean currentShowAttributeExistConfirmation = this.showAttributeExistConfirmation;
		this.showAttributeExistConfirmation = false;

		if(!validationToggle){
			attribute.setRegExp(null);
		}

		if(!tooltipToggle){
			attribute.setGluuTooltip(null);
		}
		
		if ((attribute.getEditType() != null) && attribute.getEditType().length == 0) {
			attribute.setEditType(null);
		}

		if ((attribute.getViewType() != null) && attribute.getViewType().length == 0) {
			attribute.setViewType(null);
		}
		
		String attributeName = this.attribute.getName();
		if (this.update) {
			try {
				
				// Check if attribute defined in gluuPerson or in custom object class
				boolean containsAttribute = containsAttributeInGluuObjectClasses(attributeName);
				if (!containsAttribute) {
					// Check if the attribute defined 
					String atributeNameToSearch = StringHelper.toLowerCase(attributeName);
					if (schemaService.containsAttributeTypeInSchema(atributeNameToSearch)) {
						String customObjectClass = attributeService.getCustomOrigin();
						boolean addResult = addAttributeToObjectClass(attributeName, attributeService.getCustomOrigin());
						if (!addResult) {
							facesMessages.add(Severity.ERROR, "Failed to add attribute type '{0}' to LDAP schema object class '{1}'", attributeName, customObjectClass);
							return OxTrustConstants.RESULT_FAILURE;
						}
					} else {
						facesMessages.add(Severity.ERROR, "There is no attribute type '{0}' definition in LDAP schema", attributeName);
						return OxTrustConstants.RESULT_FAILURE;
					}
				}

				attributeService.updateAttribute(this.attribute);
			} catch (LdapMappingException ex) {
				log.error("Failed to update attribute {0}", ex, inum);
				facesMessages.add(Severity.ERROR, "Failed to update attribute");
				return OxTrustConstants.RESULT_FAILURE;
			}
		} else {
			if (!validateName(attributeName)) {
				return OxTrustConstants.RESULT_FAILURE;
			}

			if (schemaService.containsAttributeTypeInSchema(attributeName)) {
				if (currentShowAttributeExistConfirmation) {
					if (addToSchema) {
						facesMessages.addToControl("nameId", Severity.ERROR,
								"Attribute with specified name already exist in server schema definition");
						return OxTrustConstants.RESULT_FAILURE;
					}
				} else {
					this.showAttributeExistConfirmation = true;
					return OxTrustConstants.RESULT_CONFIRM;
				}
			}

			boolean result = addNewAttribute(attributeName, addToSchema);
			if (!result) {
				return OxTrustConstants.RESULT_FAILURE;
			}
		}

		this.update = true;
		if (!loadAttribute(this.attribute.getInum())) {
			return OxTrustConstants.RESULT_FAILURE;
		}

		return OxTrustConstants.RESULT_SUCCESS;
	}

	private boolean addNewAttribute(String attributeName, boolean addToSchema) {
		log.info("getting attribute inum : " + attributeService.generateInumForNewAttribute());
		String inum = attributeService.generateInumForNewAttribute();
		log.info("getting the dn : " + attributeService.getDnForAttribute(inum));
		String dn = attributeService.getDnForAttribute(inum);
		log.info("getting ldapAttributeName : " + attributeService.generateRandomOid());
		String ldapAttributedName = attributeService.generateRandomOid();
		log.info("getting objectClassName : " + attributeService.getCustomOrigin());
		String objectClassName = attributeService.getCustomOrigin();
		if (attribute.getSaml1Uri() == null || attribute.getSaml1Uri().equals("")) {
			attribute.setSaml1Uri("urn:gluu:dir:attribute-def:" + attributeName);
		}
		if (attribute.getSaml2Uri() == null || attribute.getSaml2Uri().equals("")) {
			attribute.setSaml2Uri("urn:oid:" + attributeName);
		}

		if (addToSchema) {
			// Add new attribute type to LDAP schema
			try {
				log.info("adding string attribute");
				log.info("ldapAttributedName : " + ldapAttributedName);
				log.info("attributeName : " + attributeName);
	
				schemaService.addStringAttribute(ldapAttributedName, attributeName, applicationConfiguration.getSchemaAddAttributeDefinition());
			} catch (Exception ex) {
				log.error("Failed to add new attribute type to LDAP schema", ex);
	
				facesMessages.add(Severity.ERROR, "Failed to add attribute type '{0}' to LDAP schema", attributeName);
				return false;
			}
	
			boolean addResult = addAttributeToObjectClass(attributeName, objectClassName);
			if (!addResult) {
				facesMessages.add(Severity.ERROR, "Failed to add attribute type '{0}' to LDAP schema object class '{1}'", attributeName, objectClassName);
				return false;
			}
		} else {
			String attributeOrigin = determineOrigin(attributeName);
			if (StringHelper.isEmpty(attributeOrigin)) {
				facesMessages.add(Severity.ERROR, "Failed to determine object class by attribute name");
				return false;
			}

			this.attribute.setOrigin(attributeOrigin);

			// Check if attribute defined in gluuPerson or in custom object class
			boolean containsAttribute = containsAttributeInGluuObjectClasses(attributeName);
			if (!containsAttribute) {
				String customObjectClass = attributeService.getCustomOrigin();
				boolean addResult = addAttributeToObjectClass(attributeName, customObjectClass);
				if (!addResult) {
					facesMessages.add(Severity.ERROR, "Failed to add attribute type '{0}' to LDAP schema object class '{1}'", attributeName, customObjectClass);
					return false;
				}
			}
		}

		// Save attribute metadata
		this.attribute.setDn(dn);
		this.attribute.setInum(inum);

		try {
			attributeService.addAttribute(this.attribute);
		} catch (LdapMappingException ex) {
			log.error("Failed to add new attribute {0}", ex, this.attribute.getInum());

			facesMessages.add(Severity.ERROR, "Failed to add new attribute");
			return false;
		}

		return true;
	}

	private String determineOrigin(String attributeName) {
		SchemaEntry schemaEntry = schemaService.getSchema();
		Set<String> objectClasses = schemaService.getObjectClassesByAttribute(schemaEntry, attributeName);
		if (objectClasses.size() == 0) {
			log.error("Failed to determine object class by attribute name '{0}'", attributeName);
			return null;
		}

		List<String> attributeOriginins = attributeService.getAllAttributeOrigins();
		String customOriginin = attributeService.getCustomOrigin();
		attributeOriginins.remove(customOriginin);

		for (Iterator<String> it = objectClasses.iterator(); it.hasNext();) {
			String attributeOrigin = (String) it.next();

			// Try to determine one which we supports
			if (attributeOriginins.contains(attributeOrigin)) {
				return attributeOrigin;
			}
		}

		// Use first one
		String attributeOrigin = objectClasses.iterator().next();
		return attributeOrigin;
	}

	private boolean containsAttributeInGluuObjectClasses(String attributeName) {
		String[] objectClasses = { "gluuPerson", attributeService.getCustomOrigin() };

		SchemaEntry schemaEntry = schemaService.getSchema();
		Set<String> attributeNames = schemaService.getObjectClassesAttributes(schemaEntry, objectClasses);

		String atributeNameToSearch = StringHelper.toLowerCase(attributeName);
		boolean result = attributeNames.contains(atributeNameToSearch);

		return result;
	}

	private boolean addAttributeToObjectClass(String attributeName, String objectClassName) {
		// Register new attribute type to custom object class
		try {
			schemaService.addAttributeTypeToObjectClass(objectClassName, attributeName);
		} catch (Exception ex) {
			log.error("Failed to add new attribute type to LDAP schema's object class", ex);

			return false;
		}
		
		return true;
	}

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public String delete() {
		showAttributeDeleteConfirmation = true;
		return deleteAndAcceptUpdate();
	}

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public void cancelDeleteAndAcceptUpdate() {
		showAttributeDeleteConfirmation = false;
	}

	@Restrict("#{s:hasPermission('attribute', 'access')}")
	public String deleteAndAcceptUpdate() {
		if (update && showAttributeDeleteConfirmation && this.attribute.isCustom()) {
			showAttributeDeleteConfirmation = false;

			if (attributeService.removeAttribute(this.attribute)) {
				return OxTrustConstants.RESULT_SUCCESS;
			} else {
				log.error("Failed to remove attribute {0}", this.attribute.getInum());
			}
		}

		showAttributeDeleteConfirmation = false;

		return OxTrustConstants.RESULT_FAILURE;
	}

	public boolean validateEditType() {
		if (!(this.attribute.allowEditBy(GluuUserRole.USER) || this.attribute.allowEditBy(GluuUserRole.ADMIN))) {
			facesMessages.add(Severity.WARN, "Please select Edit Type.");
			return false;
		}

		return true;
	}

	public boolean validateName(String attributeName) {
		GluuAttribute tmpAttribute = new GluuAttribute();
		tmpAttribute.setBaseDn(attributeService.getDnForAttribute(null));
		tmpAttribute.setName(attributeName);

		if (attributeService.containsAttribute(tmpAttribute)) {
			facesMessages.addToControl("nameId", Severity.ERROR, "Attribute with specified name already exist");
			return false;
		}

		return true;
	}

	public String getInum() {
		return inum;
	}
	public void setInum(String inum) {
		this.inum = inum;
	}

	public GluuAttribute getAttribute() {
		return attribute;
	}

	public boolean isUpdate() {
		return update;
	}

	public boolean isShowAttributeDeleteConfirmation() {
		return showAttributeDeleteConfirmation;
	}

	public boolean isShowAttributeExistConfirmation() {
		return showAttributeExistConfirmation;
	}

	public boolean canEdit() {
		return canEdit;
	}

	public boolean canEditUri() {
		return attributeService.getCustomOrigin().equals(attribute.getOrigin());
	}

	public boolean isValidationToggle() {
		return validationToggle;
	}

	public void setValidationToggle(boolean validationToggle) {
		this.validationToggle = validationToggle;
	}

	public boolean isTooltipToggle() {
		return tooltipToggle;
	}

	public void setTooltipToggle(boolean tooltipToggle) {
		this.tooltipToggle = tooltipToggle;
	}

}
