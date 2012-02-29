package org.onehippo.forge.konakart.replication.factory;

import com.konakart.app.Product;
import com.konakart.appif.LanguageIf;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.value.DoubleValue;
import org.joda.time.DateTime;
import org.onehippo.forge.konakart.common.KKCndConstants;
import org.onehippo.forge.konakart.replication.utils.NodeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.version.VersionManager;

/**
 * To use the replication plugin, you need to add a class that will extend this class to define
 * the following information :
 * - ProductDocType : Define the name of the document type which defines a product.
 * - KonakartProductPropertyName : Define the name of the node which contains the konakart product
 * <p/>
 * See the class org.onehippo.forge.konakart.demo.MyProductFactory within the demo project
 * - ProductDocType: myhippoproject:productdocument
 * - KonakartProductPropertyName : myhippoproject:konakart
 */
public abstract class AbstractProductFactory implements ProductFactory {

    private static Logger log = LoggerFactory.getLogger(AbstractProductFactory.class);

    private VersionManager versionManager;

    protected javax.jcr.Session session;
    private NodeHelper nodeHelper;
    private String contentRoot;
    private String productDocType;
    private String kkProductTypeName;
    private String konakartProductPropertyName;

    public void setSession(Session session) throws RepositoryException {
        this.session = session;
        nodeHelper = new NodeHelper(session);
        versionManager = session.getWorkspace().getVersionManager();
    }

    public void setContentRoot(String contentRoot) {
        // Add a / at the end of the content root
        if (!StringUtils.endsWith(contentRoot, "/")) {
            contentRoot +=  "/";
        }

        this.contentRoot = contentRoot;
    }
    
    public String createReviewFolder(String reviewFolder) throws Exception {

        if (StringUtils.isEmpty(reviewFolder)) {
            reviewFolder = KKCndConstants.DEFAULT_REVIEWS_FOLDER;
        }

        nodeHelper.createMissingFolders(contentRoot + reviewFolder);

        return nodeHelper.encodeName(reviewFolder);
    }

    public void setKonakartProductPropertyName(String konakartProductPropertyName) {
        this.konakartProductPropertyName = konakartProductPropertyName;
    }

    public void setProductDocType(String productDocType) {
        this.productDocType = productDocType;
    }

    @Override
    public void setKKProductTypeName(String productTypeName) {
        this.kkProductTypeName = productTypeName;
    }

    @Override
    public String add(Product product, LanguageIf language) throws Exception {

        Node productNode = null;

        // Try to get the product node by identifier
        if (!StringUtils.isEmpty(product.getCustom1())) {
            try {
                Node productHandle = session.getNodeByIdentifier(product.getCustom1());

                if (productHandle != null) {
                    String encodingName = nodeHelper.uriEncoding.encode(product.getName());
                    productNode = productHandle.getNode(encodingName);
                }
            } catch (RepositoryException e) {
                // not found do nothing
                log.warn("Unable to find the product with the UUID - " + product.getCustom1() + " - " + e.toString());
            }
        }

        // Try to get the product node by product's id
        if (productNode == null) {
            // Try to find the product
            QueryManager queryManager = session.getWorkspace().getQueryManager();

            String searchProduct = "select * from konakart:konakart where konakart:id = " + product.getId();

            Query query = queryManager.createQuery(searchProduct, Query.SQL);

            QueryResult result = query.execute();

            NodeIterator iterator = result.getNodes();

            long numberOfProducts = iterator.getSize();

            // A product has been found
            if (numberOfProducts > 0) {

                // Number of products must be equals to 1
                if (numberOfProducts > 1) {
                    log.error(numberOfProducts + " has been found for the Konakart product's id: " + product.getId());
                }


                productNode = iterator.nextNode();
            }
        }

        // No node has been found. So add a new one.
        if (productNode == null) {
            // add the product node root
            String absPath = createProductNodeRoot(product);

            // Create the root
            Node rootFolder = nodeHelper.createMissingFolders(absPath);

            // Create the document
            productNode = nodeHelper.createDocument(rootFolder, product, productDocType,
                    session.getUserID(), language.getCode());

            if (log.isInfoEnabled()) {
                log.info("The konakart product with id : {} has been added", product.getId());
            }
        }

        boolean hasCheckout = false;

        // Check if the node is check-in
        if (!productNode.isCheckedOut()) {
            versionManager.checkout(productNode.getPath());
            hasCheckout = true;
        }

        // Set the state of the product
        String state = (product.getStatus() == 0) ? NodeHelper.UNPUBLISHED_STATE : NodeHelper.PUBLISHED_STATE;
        nodeHelper.updateState(productNode, state);

        // Update the node
        updateProperties(product, productNode);

        // Create the konakart ref product
        createOrUpdateKonakartProduct(product, productNode, language.getId());

        // Save the node
        productNode.getSession().save();

        if (hasCheckout) {
            versionManager.checkin(productNode.getPath());
        }

        // Return the hanlde's UUID of the product.
        return productNode.getParent().getIdentifier();

    }

    /**
     * Create the absolute path where the product will be created.
     *
     * @param product a product
     * @return the absolute path where the product will be created
     */
    private String createProductNodeRoot(Product product) {

        String absPath = contentRoot + "/" + kkProductTypeName + "/";

        // Get the manufacturer name
        absPath += product.getManufacturerName();

        // Get the creation time
        DateTime dateTime = new DateTime(product.getDateAdded().getTime().getTime());
        absPath += "/" + dateTime.getYear() + "/" + dateTime.getMonthOfYear();

        return absPath;
    }

    /**
     * Create or update the konakart node.
     *
     *
     * @param product     the konakart product
     * @param productNode the hippo product's node
     * @param languageId language's id within konakart
     * @throws RepositoryException if any exception occurs
     */
    private void createOrUpdateKonakartProduct(Product product, Node productNode, int languageId) throws RepositoryException {

        Node konakartNode;
        Node descriptionNode;
        Node standardPriceNode;

        // Create the node
        if (!productNode.hasNode(konakartProductPropertyName)) {
            konakartNode = productNode.addNode(konakartProductPropertyName, KKCndConstants.PRODUCT_DOC_TYPE);
            konakartNode.setProperty(KKCndConstants.PRODUCT_ID, (long) product.getId());
            descriptionNode = konakartNode.addNode(KKCndConstants.PRODUCT_DESCRIPTION, "hippostd:html");
            standardPriceNode = konakartNode.addNode(KKCndConstants.PRODUCT_STANDARD_PRICE, KKCndConstants.CP_PRICE_TYPE);
        } else {
            konakartNode = productNode.getNode(konakartProductPropertyName);
            descriptionNode = konakartNode.getNode(KKCndConstants.PRODUCT_DESCRIPTION);
            standardPriceNode = konakartNode.getNode(KKCndConstants.PRODUCT_STANDARD_PRICE);
        }

        konakartNode.setProperty(KKCndConstants.PRODUCT_NAME, product.getName());
        konakartNode.setProperty(KKCndConstants.PRODUCT_SKU, product.getSku());
        konakartNode.setProperty(KKCndConstants.PRODUCT_LANGUAGE_ID, languageId);
        konakartNode.setProperty(KKCndConstants.PRODUCT_MANUFACTURER, product.getManufacturerName());
        konakartNode.setProperty(KKCndConstants.PRODUCT_MANUFACTURER_ID, product.getManufacturerId());

        if (product.getPrice0() != null) {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_0, new DoubleValue(product.getPrice0().doubleValue()));
        } else {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_0, new DoubleValue(0));
        }

        if (product.getPrice1() != null) {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_1, new DoubleValue(product.getPrice1().doubleValue()));
        } else {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_1, new DoubleValue(0));
        }

        if (product.getPrice2() != null) {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_2, new DoubleValue(product.getPrice2().doubleValue()));
        } else {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_2, new DoubleValue(0));
        }

        if (product.getPrice3() != null) {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_3, new DoubleValue(product.getPrice3().doubleValue()));
        } else {
            standardPriceNode.setProperty(KKCndConstants.CP_PRICE_3, new DoubleValue(0));
        }

        if (product.getSpecialPriceExTax() != null) {
            konakartNode.setProperty(KKCndConstants.PRODUCT_SPECIAL_PRICE, product.getSpecialPriceExTax().doubleValue());
        }

        // Set the description property
        String description = "<html><body>";

        if (product.getDescription() != null) {
            description += product.getDescription();
        }

        description += "</body></html>";

        descriptionNode.setProperty("hippostd:content", description);
    }

    /**
     * This is an helper method that could be used to set others information defined into Konakart but not
     * already integrated within the konakart:konakart document.
     *
     * @param product the konakart's product
     * @param node    the product's node
     */
    protected abstract void updateProperties(Product product, Node node);
}