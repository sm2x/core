package com.dotmarketing.webdav;

import static com.dotmarketing.business.PermissionAPI.PERMISSION_CAN_ADD_CHILDREN;
import static com.dotmarketing.business.PermissionAPI.PERMISSION_READ;

import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Timer;

import org.apache.velocity.runtime.resource.ResourceManager;
import com.dotcms.repackage.org.apache.commons.lang.StringUtils;

import com.dotcms.repackage.com.bradmcevoy.http.CollectionResource;
import com.dotcms.repackage.com.bradmcevoy.http.LockInfo;
import com.dotcms.repackage.com.bradmcevoy.http.LockResult;
import com.dotcms.repackage.com.bradmcevoy.http.LockTimeout;
import com.dotcms.repackage.com.bradmcevoy.http.LockToken;
import com.dotcms.repackage.com.bradmcevoy.http.Resource;
import com.dotcms.repackage.org.apache.oro.text.regex.MalformedPatternException;
import com.dotcms.repackage.org.apache.oro.text.regex.Perl5Compiler;
import com.dotcms.repackage.org.apache.oro.text.regex.Perl5Matcher;
import com.dotmarketing.beans.Host;
import com.dotmarketing.beans.Identifier;
import com.dotmarketing.beans.Permission;
import com.dotmarketing.beans.Tree;
import com.dotmarketing.beans.WebAsset;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.CacheLocator;
import com.dotmarketing.business.IdentifierAPI;
import com.dotmarketing.business.NoSuchUserException;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.business.Permissionable;
import com.dotmarketing.business.UserAPI;
import com.dotmarketing.business.Versionable;
import com.dotmarketing.cache.FolderCache;
import com.dotmarketing.cache.LiveCache;
import com.dotmarketing.cache.WorkingCache;
import com.dotmarketing.cms.factories.PublicCompanyFactory;
import com.dotmarketing.cms.login.factories.LoginFactory;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotHibernateException;
import com.dotmarketing.exception.DotRuntimeException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.factories.PublishFactory;
import com.dotmarketing.factories.TreeFactory;
import com.dotmarketing.factories.WebAssetFactory;
import com.dotmarketing.menubuilders.RefreshMenus;
import com.dotmarketing.portlets.contentlet.business.HostAPI;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.fileassets.business.FileAssetAPI;
import com.dotmarketing.portlets.fileassets.business.IFileAsset;
import com.dotmarketing.portlets.files.business.FileAPI;
import com.dotmarketing.portlets.files.model.File;
import com.dotmarketing.portlets.folders.business.FolderAPI;
import com.dotmarketing.portlets.folders.model.Folder;
import com.dotmarketing.portlets.languagesmanager.model.Language;
import com.dotmarketing.portlets.structure.model.Field;
import com.dotmarketing.portlets.structure.model.Structure;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.InodeUtils;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.UUIDGenerator;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.velocity.DotResourceCache;
import com.liferay.portal.auth.AuthException;
import com.liferay.portal.auth.Authenticator;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.User;
import com.liferay.portal.util.PropsUtil;
import com.liferay.util.FileUtil;

public class DotWebdavHelper {

	private static final FileAPI fileAPI = APILocator.getFileAPI();
	private String authType = PublicCompanyFactory.getDefaultCompany().getAuthType();
	private static String PRE_AUTHENTICATOR = PropsUtil.get("auth.pipeline.pre");
	private static ThreadLocal<Perl5Matcher> localP5Matcher = new ThreadLocal<Perl5Matcher>(){
		protected Perl5Matcher initialValue() {
			return new Perl5Matcher();
		}
	};
	private  com.dotcms.repackage.org.apache.oro.text.regex.Pattern tempResourcePattern;
	private java.io.File tempHolderDir;
	private String tempFolderPath = "dotwebdav";

	private HostAPI hostAPI = APILocator.getHostAPI();
	private FolderAPI folderAPI = APILocator.getFolderAPI();
	private IdentifierAPI idapi = APILocator.getIdentifierAPI();
	private FolderCache fc = CacheLocator.getFolderCache();
	private PermissionAPI perAPI = APILocator.getPermissionAPI();
	private static FileResourceCache fileResourceCache = new FileResourceCache();
	private long defaultLang = APILocator.getLanguageAPI().getDefaultLanguage().getId();
	private boolean legacyPath = Config.getBooleanProperty("WEBDAV_LEGACY_PATHING", false);

	/**
	 * MD5 message digest provider.
	 */
	private static MessageDigest md5Helper;


	private Hashtable<String, com.dotcms.repackage.com.bradmcevoy.http.LockInfo> resourceLocks = new Hashtable<String, com.dotcms.repackage.com.bradmcevoy.http.LockInfo>();

	static {
		new Timer().schedule(new FileResourceCacheCleaner(), 1000  * 60 * Config.getIntProperty("WEBDAV_CLEAR_RESOURCE_CACHE_FRECUENCY", 10), 1000  * 60 * Config.getIntProperty("WEBDAV_CLEAR_RESOURCE_CACHE_FRECUENCY", 10));
	}

	public DotWebdavHelper() {
		Perl5Compiler c = new Perl5Compiler();
		try{
			tempResourcePattern = c.compile("/\\(.*\\)|/._\\(.*\\)|/\\.|^\\.|^\\(.*\\)",Perl5Compiler.READ_ONLY_MASK);
    	}catch (MalformedPatternException mfe) {
    		Logger.fatal(this,"Unable to instaniate webdav servlet : " + mfe.getMessage(),mfe);
			Logger.error(this,mfe.getMessage(),mfe);
		}

    	try {
			tempHolderDir = java.io.File.createTempFile("placeHolder", "dot");
			String tp = tempHolderDir.getParentFile().getPath() + java.io.File.separator + tempFolderPath;
			FileUtil.deltree(tempHolderDir);
			tempHolderDir = new java.io.File(tp);
			tempHolderDir.mkdirs();
		} catch (IOException e1) {
			Logger.error(this, "Unable to setup temp folder for webdav");
			Logger.error(this, e1.getMessage() ,e1);
		}
		// Load the MD5 helper used to calculate signatures.
		try {
			md5Helper = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotRuntimeException("No MD5", e);
		}

	}

	//Check if the contentlets to upload are going to be live or working
	public boolean isAutoPub(String path){
		if(legacyPath){
			if(path.startsWith("/webdav/autopub")){
				return true;
			}
				return false;
		}else{
			if(path.startsWith("/webdav/live")){
				return true;
			}
				return false;
		}
	}

	public User authorizePrincipal(String username, String passwd)	throws DotSecurityException, NoSuchUserException, DotDataException {
		User _user;

		boolean useEmailAsLogin = true;
		Company comp = com.dotmarketing.cms.factories.PublicCompanyFactory.getDefaultCompany();
		if (comp.getAuthType().equals(Company.AUTH_TYPE_ID)) {
			useEmailAsLogin = false;
		}
		try {
			if (PRE_AUTHENTICATOR != null && !PRE_AUTHENTICATOR.equals("")) {
				Authenticator authenticator;
				authenticator = (Authenticator) new com.dotcms.repackage.bsh.Interpreter().eval("new " + PRE_AUTHENTICATOR + "()");
				if (useEmailAsLogin) {
					authenticator.authenticateByEmailAddress(comp.getCompanyId(), username, passwd);
				} else {
					authenticator.authenticateByUserId(comp.getCompanyId(), username, passwd);
				}
			}
		}catch (AuthException ae) {
			Logger.debug(this, "Username : " + username + " failed to login", ae);
			throw new DotSecurityException(ae.getMessage(),ae);
		}catch (Exception e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotSecurityException(e.getMessage(),e);
		}
		UserAPI userAPI=APILocator.getUserAPI();
		if (comp.getAuthType().equals(Company.AUTH_TYPE_ID)) {
			_user = userAPI.loadUserById(username,userAPI.getSystemUser(),false);
		} else {
			_user = userAPI.loadByUserByEmail(username, userAPI.getSystemUser(), false);
		}

        if (_user == null) {
            throw new DotSecurityException("The user was returned NULL");
        }

        // Validate password and rehash when is needed
        if (LoginFactory.passwordMatch(passwd, _user)) {
            return _user;
        } else {
            Logger.debug(this, "The user's passwords didn't match");
            throw new DotSecurityException("The user's passwords didn't match");
        }
	}

	public boolean isFolder(String uriAux, User user) throws IOException {
		Logger.debug(this, "isFolder");
		boolean returnValue = false;
		Logger.debug(this, "Method isFolder: the uri is " + uriAux);
		if (uriAux.equals("") || uriAux.equals("/")) {
			returnValue = true;
		} else {
			uriAux = stripMapping(uriAux);
			String hostName = getHostname(uriAux);
			Logger.debug(this, "Method isFolder: the hostname is " + hostName);
			Host host;
			try {
				host = hostAPI.findByName(hostName, user, false);
			} catch (DotDataException e) {
				Logger.error(DotWebdavHelper.class, e.getMessage(), e);
				throw new IOException(e.getMessage());
			} catch (DotSecurityException e) {
				Logger.error(DotWebdavHelper.class, e.getMessage(), e);
				throw new IOException(e.getMessage());
			}
			if (host != null && InodeUtils.isSet(host.getInode())) {
				String path = getPath(uriAux);
				Logger.debug(this, "Method isFolder: the path is " + path);
				if (path.equals("") || path.equals("/")) {
					returnValue = true;
				} else {
					if(!path.endsWith("/"))
						path += "/";
					if(path.contains("mvdest2")){
						Logger.debug(this, "is mvdest2 a folder");
					}
					Folder folder = new Folder();
					try {
						folder = folderAPI.findFolderByPath(path, host,user,false);
					} catch (Exception e) {
						Logger.debug(this, "unable to find folder " + path );
						//throw new IOException(e.getMessage());
					}
					if (InodeUtils.isSet(folder.getInode())) {
						returnValue = true;
					}
				}
			}
		}
		return returnValue;
	}

	public boolean isResource(String uri, User user) throws IOException {
		uri = stripMapping(uri);
		Logger.debug(this.getClass(), "In the Method isResource");
		if (uri.endsWith("/")) {
			return false;
		}
		boolean returnValue = false;
		// Host
		String hostName = getHostname(uri);

		Host host;
		try {
			host = hostAPI.findByName(hostName, user, false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}

		if(host == null){
			Logger.debug(this, "isResource Method: Host is NULL");
		}else{
			Logger.debug(this, "isResource Method: host id is " + host.getIdentifier() + " and the host name is " + host.getHostname());
		}
		// Folder
		String path = getPath(uri);
		String folderName = getFolderName(path);
		Folder folder;
		try {
			folder = folderAPI.findFolderByPath(folderName, host, user,false);
		} catch (Exception e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}
		if(folder!=null && InodeUtils.isSet(folder.getInode())) {
    		// FileName
    		String fileName = getFileName(path);
    		fileName = deleteSpecialCharacter(fileName);

    		if (InodeUtils.isSet(host.getInode())) {
    			try {
    				returnValue = fileAPI.fileNameExists(folder, fileName);
    				if(!returnValue){
    					returnValue = APILocator.getFileAssetAPI().fileNameExists(host, folder, fileName, "");
    				}
    			} catch (Exception ex) {
    				Logger.debug(this, "Error verifying if file already exists",ex);
    			}
    		}
		}
		return returnValue;
	}

	public IFileAsset loadFile(String url, User user) throws IOException, DotDataException, DotSecurityException{
		url = stripMapping(url);
		String hostName = getHostname(url);
		url = getPath(url);

		Host host;
		try {
			host = hostAPI.findByName(hostName, user, false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}

		IFileAsset f =null;
		try {
    		Identifier id  = APILocator.getIdentifierAPI().find(host, url);
    		if(id!=null && InodeUtils.isSet(id.getId())) {
    		    if(id.getAssetType().equals("contentlet")){
    		        Contentlet cont = APILocator.getContentletAPI().findContentletByIdentifier(id.getId(), false, defaultLang, user, false);
    	            if(cont!=null && InodeUtils.isSet(cont.getIdentifier()) && !APILocator.getVersionableAPI().isDeleted(cont)){
    	                f = APILocator.getFileAssetAPI().fromContentlet(cont);
    	            }
    		    }
    		    else {
    	             f = fileAPI.getFileByURI(url, host, false, user, false);
    	        }
    		}
		}catch (Exception ex) {
		    f = null;
		}

		return f;
	}

	public Folder loadFolder(String url,User user) throws IOException{
		url = stripMapping(url);
		String hostName = getHostname(url);
		url = getPath(url);
		Host host;
		Folder folder;
		try {
			host = hostAPI.findByName(hostName, user, false);
			folder = folderAPI.findFolderByPath(url, host,user, false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}

		return folder;
	}

	public java.io.File loadTempFile(String url){
		try {
			url = stripMapping(url);
		} catch (IOException e) {
			Logger.error( this, "Error happened with uri: [" + url + "]", e);
		}
		Logger.debug(this, "Getting temp file from path " + url);
		java.io.File f = new java.io.File(tempHolderDir.getPath() + url);
		return f;
	}

    /**
     * Returns a collection of child Resources for a given folder
     *
     * @param parentFolder Parent folder
     * @param user         Authenticated user
     * @param isAutoPub
     * @return
     * @throws IOException
     */
    public List<Resource> getChildrenOfFolder ( Folder parentFolder, User user, boolean isAutoPub, long lang ) throws IOException {

        String prePath = "/webdav/";
        if(legacyPath){
        	if ( isAutoPub ) {
            	prePath += "autopub/";
        	} else {
            	prePath += "nonpub/";
        	}
        }else{
        	if ( isAutoPub ) {
            	prePath += "live/";
        	} else {
            	prePath += "working/";
        	}
        	defaultLang = lang;
        	prePath += defaultLang;
        	prePath += "/";
        }

        Host folderHost;
        try {
            folderHost = hostAPI.find( parentFolder.getHostId(), user, false );
        } catch ( DotDataException e ) {
            Logger.error( DotWebdavHelper.class, e.getMessage(), e );
            throw new IOException( e.getMessage() );
        } catch ( DotSecurityException e ) {
            Logger.error( DotWebdavHelper.class, e.getMessage(), e );
            throw new IOException( e.getMessage() );
        }

        List<Resource> result = new ArrayList<Resource>();
        try {

            //Search for child folders
            List<Folder> folderListSubChildren = folderAPI.findSubFolders( parentFolder, user, false );
            //Search for child files
            List<Versionable> filesListSubChildren = new ArrayList<Versionable>();
            try {
                filesListSubChildren.addAll( folderAPI.getWorkingFiles( parentFolder, user, false ) );
                filesListSubChildren.addAll( APILocator.getFileAssetAPI().findFileAssetsByFolder( parentFolder, user, false ) );
            } catch ( Exception e2 ) {
                Logger.error( this, "Could not load files : ", e2 );
            }

            for ( Versionable file : filesListSubChildren ) {
                if ( !file.isArchived() ) {
                    IFileAsset fileAsset = (IFileAsset) file;
                    if(fileAsset.getLanguageId()==defaultLang){
                    	FileResourceImpl resource = new FileResourceImpl( fileAsset, prePath + folderHost.getHostname() + "/" + fileAsset.getPath() );
                    	result.add( resource );
                    }
                }
            }
            for ( Folder folder : folderListSubChildren ) {
                if ( !folder.isArchived() ) {
                    String path = idapi.find( folder ).getPath();

                    FolderResourceImpl resource = new FolderResourceImpl( folder, prePath + folderHost.getHostname() + "/" + (path.startsWith( "/" ) ? path.substring( 1 ) : path) );
                    result.add( resource );
                }
            }

            String p = APILocator.getIdentifierAPI().find( parentFolder ).getPath();
            if ( p.contains( "/" ) )
                p.replace( "/", java.io.File.separator );
            java.io.File tempDir = new java.io.File( tempHolderDir.getPath() + java.io.File.separator + folderHost.getHostname() + p );
            p = idapi.find( parentFolder ).getPath();
            if ( !p.endsWith( "/" ) )
                p = p + "/";
            if ( !p.startsWith( "/" ) )
                p = "/" + p;
            if ( tempDir.exists() && tempDir.isDirectory() ) {
                java.io.File[] files = tempDir.listFiles();
                for ( java.io.File file : files ) {
                    String tp = prePath + folderHost.getHostname() + p + file.getName();
                    if ( !isTempResource( tp ) ) {
                        continue;
                    }
                    if ( file.isDirectory() ) {
                        TempFolderResourceImpl tr = new TempFolderResourceImpl( tp, file, isAutoPub );
                        result.add( tr );
                    } else {
                        TempFileResourceImpl tr = new TempFileResourceImpl( file, tp, isAutoPub );
                        result.add( tr );
                    }
                }
            }
        } catch ( Exception e ) {
            Logger.error( DotWebdavHelper.class, e.getMessage(), e );
            throw new IOException( e.getMessage() );
        }

        return result;
    }

    public java.io.File getTempDir () {
        return tempHolderDir;
    }

    public String getHostName ( String uri ) {
		try {
			return getHostname(stripMapping(uri));
		} catch (IOException e) {
			Logger.error( this, "Error happened with uri: [" + uri + "]", e);
			return null;
		}
	}

	public boolean isTempResource(String path){
		Perl5Matcher matcher = (Perl5Matcher) localP5Matcher.get();
		if(matcher.contains(path, tempResourcePattern))
			return true;
		return false;
	}

	public java.io.File createTempFolder(String path){
		try {
			path = stripMapping(path);
		} catch (IOException e) {
			Logger.error( this, "Error happened with uri: [" + path + "]", e);
		}
		if(path.startsWith(tempHolderDir.getPath()))
			path = path.substring(tempHolderDir.getPath().length(), path.length());
		if(path.startsWith("/") || path.startsWith("\\")){
			path = path.substring(1, path.length());
		}
		path = path.replace("/", java.io.File.separator);
		java.io.File f = new java.io.File(tempHolderDir.getPath() + java.io.File.separator + path);
		f.mkdirs();
		return f;
	}

	public void copyFolderToTemp(Folder folder, java.io.File tempFolder, User user, String name,boolean isAutoPub, long lang) throws IOException{
		String p = "";
		try {
			p = idapi.find(folder).getPath();
		} catch (Exception e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new DotRuntimeException(e.getMessage(), e);
		}
		if(p.endsWith("/"))
			p = p + "/";
		String path = p.replace("/", java.io.File.separator);
		path = tempFolder.getPath() + java.io.File.separator + name;
		java.io.File tf = createTempFolder(path);
		List<Resource> children = getChildrenOfFolder(folder, user, isAutoPub, lang);
		for (Resource resource : children) {
			if(resource instanceof CollectionResource){
				FolderResourceImpl fr = (FolderResourceImpl)resource;
				copyFolderToTemp(fr.getFolder(), tf, user, fr.getFolder().getName(),isAutoPub, lang);
			}else{
				FileResourceImpl fr = (FileResourceImpl)resource;
				copyFileToTemp(fr.getFile(), tf);
			}
		}
	}

	public java.io.File copyFileToTemp(IFileAsset file, java.io.File tempFolder) throws IOException{
		java.io.File f = null;
		if(file instanceof Contentlet){
			f = ((Contentlet)file).getBinary(FileAssetAPI.BINARY_FIELD);
		}else{
			f = APILocator.getFileAPI().getAssetIOFile((File)file);
		}
		java.io.File nf = new java.io.File(tempFolder.getPath() + java.io.File.separator + f.getName());
		FileUtil.copyFile(f, nf);
		return nf;
	}

	public java.io.File createTempFile(String path) throws IOException{
		java.io.File file = new java.io.File(tempHolderDir.getPath() + path);
		String p = file.getPath().substring(0,file.getPath().lastIndexOf(java.io.File.separator));
		java.io.File f = new java.io.File(p);
		f.mkdirs();
		file.createNewFile();
		return file;
	}

	public void copyTempDirToStorage(java.io.File fromFileFolder, String destPath, User user,boolean autoPublish) throws Exception{
		if(fromFileFolder == null || !fromFileFolder.isDirectory()){
			throw new IOException("The temp source file must be a directory");
		}
		destPath = stripMapping(destPath);
		if(destPath.endsWith("/"))
			destPath = destPath + "/";
		createFolder(destPath, user);
		java.io.File[] files = fromFileFolder.listFiles();
		for (java.io.File file : files) {
			if(file.isDirectory()){
				copyTempDirToStorage(file, destPath + file.getName(), user, autoPublish);
			}else{
				copyTempFileToStorage(file, destPath + file.getName(), user,autoPublish);
			}
		}
	}

	public void copyTempFileToStorage(java.io.File fromFile, String destPath,User user,boolean autoPublish) throws Exception{
		destPath = stripMapping(destPath);
		if(fromFile == null){
			throw new IOException("The temp source file must exist");
		}
		InputStream in = new FileInputStream(fromFile);
		createResource(destPath, autoPublish, user);
		setResourceContent(destPath, in, null, null, new Date(fromFile.lastModified()),user, autoPublish);
	}

	public void copyResource(String fromPath, String toPath, User user, boolean autoPublish) throws Exception {
		createResource(toPath, autoPublish, user);
		setResourceContent(toPath, getResourceContent(fromPath,user), null, null, user);
	}

	public void copyFolder(String sourcePath, String destinationPath, User user, boolean autoPublish) throws IOException, DotDataException {
		try{
			destinationPath=stripMapping(destinationPath);
			sourcePath=stripMapping(sourcePath);
			PermissionAPI perAPI = APILocator.getPermissionAPI();
			createFolder(destinationPath, user);

			Summary[] children = getChildrenData(sourcePath, user);

			for (int i = children.length - 1; i >= 0; i--) {
				// children[i] = "/" + children[i];

			if (!children[i].isFolder()) {

				createResource(destinationPath + "/" + children[i].getName(), autoPublish, user);
				setResourceContent(destinationPath + "/" + children[i].getName(), getResourceContent(sourcePath + "/" + children[i].getName(),user), null, null, user);

				// ### Copy the permission ###
				// Source
				boolean live = false;

				Identifier identifier  = APILocator.getIdentifierAPI().find(children[i].getHost(), destinationPath + "/" + children[i].getName());
				Permissionable destinationFile = null;
 				if(identifier!=null && identifier.getAssetType().equals("contentlet")){
 					destinationFile = APILocator.getContentletAPI().findContentletByIdentifier(identifier.getId(), live, defaultLang, user, false);
				}else{
					destinationFile = fileAPI.getFileByURI(destinationPath + "/" + children[i].getName(), children[i].getHost(), live, user, false);
				}

				// Delete the new permissions
				perAPI.removePermissions(destinationFile);

				// Copy the new permissions
				perAPI.copyPermissions((Permissionable)children[i].getFile(), destinationFile);

				// ### END Copy the permission ###
				// }
			} else {
				copyFolder(sourcePath + "/" + children[i].getName(), destinationPath + "/" + children[i].getName(), user, autoPublish);
			}

			}

			// ### Copy the permission ###
			// Source
			String sourceHostName = getHostname(sourcePath);
			String sourceFolderName = getPath(sourcePath);
			// String sourceFolderName = DotCMSStorage.getFolderName(sourcePath);
			Host sourceHost;

			sourceHost = hostAPI.findByName(sourceHostName, user, false);



			Folder sourceFolder = folderAPI.findFolderByPath(sourceFolderName + "/", sourceHost,user,false);
			// Destination
			String destinationHostName = getHostname(destinationPath);
			String destinationFolderName = getPath(destinationPath);
			// String destinationFolderName =
			// DotCMSStorage.getFolderName(destinationPath);
			Host destinationHost;

			destinationHost = hostAPI.findByName(destinationHostName, user, false);


			Folder destinationFolder = folderAPI.findFolderByPath(destinationFolderName + "/", destinationHost,user,false);

			// Delete the new permissions
			perAPI.removePermissions(destinationFolder);

			// Copy the new permissions
			perAPI.copyPermissions(sourceFolder, destinationFolder);
		}catch (Exception e) {
			throw new DotDataException(e.getMessage(), e);
		}
		return;
	}

	public void createResource(String resourceUri, boolean publish, User user) throws IOException, DotDataException {
		try{
			PermissionAPI perAPI = APILocator.getPermissionAPI();
			Logger.debug(this.getClass(), "createResource");
			resourceUri = stripMapping(resourceUri);
			String hostName = getHostname(resourceUri);
			String path = getPath(resourceUri);
			String folderName = getFolderName(path);
			String fileName = getFileName(path);
			fileName = deleteSpecialCharacter(fileName);
			if(fileName.startsWith(".")){
				return;
			}

			Host host;

			host = hostAPI.findByName(hostName, user, false);

			Folder folder = folderAPI.findFolderByPath(folderName, host,user,false);
			boolean hasPermission = false;

			hasPermission = (!folderName.equals("/") && perAPI.doesUserHavePermission(folder, PERMISSION_CAN_ADD_CHILDREN, user, false))
			                || (folderName.equals("/") && perAPI.doesUserHavePermission(host, PERMISSION_CAN_ADD_CHILDREN, user, false));

			if (hasPermission) {
				// Check the folder filters
				if (!checkFolderFilter(folder, fileName)) {
					throw new IOException("The file doesn't comply the folder's filter");
				}

				if (host != null && InodeUtils.isSet(host.getInode())&& InodeUtils.isSet(folder.getInode())) {

					Identifier identifier = APILocator.getIdentifierAPI().find(host,path);

					File file = new File();
					file.setTitle(fileName);
					file.setFileName(fileName);
					file.setShowOnMenu(false);
					file.setModDate(new Date());
					String mimeType = fileAPI.getMimeType(fileName);
					file.setMimeType(mimeType);
					String author = user.getFullName();
					file.setAuthor(author);
					file.setModUser(author);
					file.setSortOrder(0);
					file.setShowOnMenu(false);


					if (identifier !=null &&  InodeUtils.isSet(identifier.getId()) && !identifier.getAssetType().equals("contentlet")) {
						File actualFile = fileAPI.getFileByURI(path, host, false,user,false);
						if(!UtilMethods.isSet(actualFile.getInode())){
							actualFile = (File)APILocator.getVersionableAPI().findWorkingVersion(identifier, user, false);
							WebAssetFactory.unArchiveAsset(actualFile);
						}
						if(!UtilMethods.isSet(actualFile.getInode())){
							throw new DotDataException("unable to locate file");
						}
						//						identifier = idapi.find(actualFile);
						WebAssetFactory.createAsset(file, user.getUserId(),	folder, identifier, false, false);
						if(publish && perAPI.doesUserHavePermission(file, PermissionAPI.PERMISSION_PUBLISH, user)){
							WebAssetFactory.publishAsset(file);
						}

						// ##### Copy the file data if we are creating a new
						// version #####
						String assetsPath = fileAPI.getRealAssetsRootPath();
						new java.io.File(assetsPath).mkdir();

						// creates the new file as
						// inode{1}/inode{2}/inode.file_extension
						java.io.File workingIOFile = fileAPI.getAssetIOFile(actualFile);

						//http://jira.dotmarketing.net/browse/DOTCMS-1873
						//To clear velocity cache
						DotResourceCache vc = CacheLocator.getVeloctyResourceCache();
						vc.remove(ResourceManager.RESOURCE_TEMPLATE + workingIOFile.getPath());

						// If a new version was created, we move the current
						// data to the new version
						if (file != null && InodeUtils.isSet(file.getInode())) {
							byte[] currentData = new byte[0];
							FileInputStream is = new FileInputStream(workingIOFile);
							int size = is.available();
							currentData = new byte[size];
							is.read(currentData);
							java.io.File newVersionFile = fileAPI.getAssetIOFile(file);

							//http://jira.dotmarketing.net/browse/DOTCMS-1873
							//To clear velocity cache
							vc.remove(ResourceManager.RESOURCE_TEMPLATE + newVersionFile.getPath());

							FileChannel channelTo = new FileOutputStream(newVersionFile).getChannel();
							ByteBuffer currentDataBuffer = ByteBuffer.allocate(currentData.length);
							currentDataBuffer.put(currentData);
							currentDataBuffer.position(0);
							channelTo.write(currentDataBuffer);
							channelTo.force(false);
							channelTo.close();
							file.setSize(currentData.length);
							if (UtilMethods.isImage(fileName) && workingIOFile != null) {
								try {
									// gets image height
									BufferedImage img = javax.imageio.ImageIO.read(workingIOFile);
									if(img != null){
										int height = img.getHeight();
										file.setHeight(height);
										// gets image width
										int width = img.getWidth();
										file.setWidth(width);
									}
								} catch (Exception ioe) {
									Logger.error(this.getClass(), ioe.getMessage(), ioe);
								}
							}
							HibernateUtil.saveOrUpdate(file);
						}
						// ##### END Copy the file data if we are creating a new
						// version #####

						// Get parents of the old version so you can update the
						// working
						// information to this new version.
						java.util.List<Tree> parentTrees = TreeFactory.getTreesByChild(file);

						// update parents to new version delete old versions
						// parents if
						// not live.
						for (Tree tree : parentTrees) {
							// to keep relation types from parent only if it
							// exists
							Tree newTree = TreeFactory.getTree(tree.getParent(), file.getInode());
							if (!InodeUtils.isSet(newTree.getChild())) {
								newTree.setParent(tree.getParent());
								newTree.setChild(file.getInode());
								newTree.setRelationType(tree.getRelationType());
								newTree.setTreeOrder(0);
								TreeFactory.saveTree(newTree);
							}
						}
						APILocator.getVersionableAPI().setWorking(file);
						if(publish && perAPI.doesUserHavePermission(file, PermissionAPI.PERMISSION_PUBLISH, user))
							APILocator.getVersionableAPI().setLive(file);
						WorkingCache.removeAssetFromCache(file);
						LiveCache.removeAssetFromCache(file);
					}

				}
			} else {
				throw new IOException("You don't have access to add that folder/host");
			}
		}catch (Exception e) {
			throw new DotDataException(e.getMessage(), e);
		}

	}

	public void setResourceContent(String resourceUri, InputStream content,	String contentType, String characterEncoding, Date modifiedDate, User user, boolean isAutoPub) throws Exception {
		resourceUri = stripMapping(resourceUri);
		Logger.debug(this.getClass(), "setResourceContent");
		String hostName = getHostname(resourceUri);
		String path = getPath(resourceUri);
		String folderName = getFolderName(path);
		String fileName = getFileName(path);
		fileName = deleteSpecialCharacter(fileName);

		Host host;
		try {
			host = hostAPI.findByName(hostName, user, false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}

		Folder folder = new Folder();
		try {
			folder = folderAPI.findFolderByPath(folderName, host,user,false);
		} catch (Exception e2) {
			Logger.error(this, e2.getMessage(), e2);
		}
		if (host != null && InodeUtils.isSet(host.getInode()) && InodeUtils.isSet(folder.getInode())) {
			IFileAsset destinationFile = null;
			java.io.File workingFile = null;
			Folder parent = null;
			Contentlet fileAssetCont = null;
			Identifier identifier  = APILocator.getIdentifierAPI().find(host, path);
			if(identifier!=null && InodeUtils.isSet(identifier.getId()) && identifier.getAssetType().equals("contentlet")){
				long langContentlet = APILocator.getContentletAPI().searchByIdentifier("+identifier:" + identifier.getId(), 0, -1, null, APILocator.getUserAPI().getSystemUser(), false, PermissionAPI.PERMISSION_READ, true).get(0).getLanguageId();
				fileAssetCont = APILocator.getContentletAPI().findContentletByIdentifier(identifier.getId(), false, langContentlet, user, false);				
				workingFile = fileAssetCont.getBinary(FileAssetAPI.BINARY_FIELD);
				destinationFile = APILocator.getFileAssetAPI().fromContentlet(fileAssetCont);
				parent = APILocator.getFolderAPI().findFolderByPath(identifier.getParentPath(), host, user, false);

				if(fileAssetCont.isArchived()) {
				    APILocator.getContentletAPI().unarchive(fileAssetCont, user, false);
				}
			}else if(identifier!=null && InodeUtils.isSet(identifier.getId())){
				destinationFile = fileAPI.getFileByURI(path, host, false, user, false);
				// inode{1}/inode{2}/inode.file_extension
				workingFile = fileAPI.getAssetIOFile((File)destinationFile);

				if(destinationFile.isArchived()) {
				    WebAssetFactory.unArchiveAsset((File)destinationFile);
				}
			}

			//http://jira.dotmarketing.net/browse/DOTCMS-1873
			//To clear velocity cache
			if(workingFile!=null){
				DotResourceCache vc = CacheLocator.getVeloctyResourceCache();
				vc.remove(ResourceManager.RESOURCE_TEMPLATE + workingFile.getPath());
			}

			// If content is empty or null we cannot do anything
			if(content == null || content.available() == 0) {
                Logger.warn(this, "The 'content' param is null or empty. We are going to create an empty file.");
            }

			if(destinationFile==null){
				Contentlet fileAsset = new Contentlet();
				Structure faStructure = CacheLocator.getContentTypeCache().getStructureByInode(folder.getDefaultFileType());
				Field fieldVar = faStructure.getFieldVar(FileAssetAPI.BINARY_FIELD);
				fileAsset.setStructureInode(folder.getDefaultFileType());
				fileAsset.setFolder(folder.getInode());

				// Create user temp folder and create file inside of it
				java.io.File fileData = createFileInTemporalFolder(fieldVar, user.getUserId(), fileName);

				// Saving the new working data
				final ReadableByteChannel inputChannel = Channels.newChannel(content);
                final WritableByteChannel outputChannel = Channels.newChannel(new FileOutputStream(fileData));
                FileUtil.fastCopyUsingNio(inputChannel, outputChannel);
                Logger.debug(this, "WEBDAV fileName:" + fileName + " : File size:" + fileData.length() + " : " + fileData.getAbsolutePath());

				fileAsset.setStringProperty(FileAssetAPI.TITLE_FIELD, fileName);
				fileAsset.setStringProperty(FileAssetAPI.FILE_NAME_FIELD, fileName);
				fileAsset.setBinary(FileAssetAPI.BINARY_FIELD, fileData);
				fileAsset.setHost(host.getIdentifier());
				fileAsset.setLanguageId(defaultLang);
				fileAsset=APILocator.getContentletAPI().checkin(fileAsset, user, false);

				//Validate if the user have the right permission before
				if(isAutoPub && !perAPI.doesUserHavePermission(fileAsset, PermissionAPI.PERMISSION_PUBLISH, user) ){
					APILocator.getContentletAPI().archive(fileAsset, APILocator.getUserAPI().getSystemUser(), false);
					APILocator.getContentletAPI().delete(fileAsset, APILocator.getUserAPI().getSystemUser(), false);
					throw new DotSecurityException("User does not have permission to publish contentlets");
		        }else if(!isAutoPub && !perAPI.doesUserHavePermission(fileAsset, PermissionAPI.PERMISSION_EDIT, user)){
		        	APILocator.getContentletAPI().archive(fileAsset, APILocator.getUserAPI().getSystemUser(), false);
					APILocator.getContentletAPI().delete(fileAsset, APILocator.getUserAPI().getSystemUser(), false);
					throw new DotSecurityException("User does not have permission to edit contentlets");
		        }
				if(isAutoPub && perAPI.doesUserHavePermission(fileAsset, PermissionAPI.PERMISSION_PUBLISH, user)) {
				    APILocator.getContentletAPI().publish(fileAsset, user, false);

				    Date currentDate = new Date();
				    fileResourceCache.add(resourceUri + "|" + user.getUserId(), currentDate.getTime());
				}
			} else {
			    java.io.File fileData;
				if(destinationFile instanceof File){
					fileData=workingFile;
				} else {
					Structure faStructure = CacheLocator.getContentTypeCache().getStructureByInode(folder.getDefaultFileType());
					Field fieldVar = faStructure.getFieldVar(FileAssetAPI.BINARY_FIELD);

					// Create user temp folder and create file inside of it
	                fileData = createFileInTemporalFolder(fieldVar, user.getUserId(), fileName);
			    }

				// Saving the new working data
			    final ReadableByteChannel inputChannel = Channels.newChannel(content);
                final WritableByteChannel outputChannel = Channels.newChannel(new FileOutputStream(fileData));
                FileUtil.fastCopyUsingNio(inputChannel, outputChannel);
                Logger.debug(this, "WEBDAV fileName:" + fileName + " : File size:" + fileData.length() + " : " + fileData.getAbsolutePath());

				if(destinationFile instanceof File){
				    // Save the file size
                    File file = fileAPI.getFileByURI(path, host, false, user, false);
                    file.setSize((int) fileData.length());
                    file.setModDate(modifiedDate);
                    file.setModUser(user.getUserId());
                    try {
                        HibernateUtil.saveOrUpdate(file);
                    } catch (DotHibernateException e1) {
                        Logger.error(this,e1.getMessage(), e1);
                    }

					// checks if it's an image
					if (UtilMethods.isImage(fileName) && workingFile != null) {
						try {
							// gets image height
							BufferedImage img = javax.imageio.ImageIO.read(workingFile);
							if(img != null){
								int height = img.getHeight();
								((File)destinationFile).setHeight(height);
								// gets image width
								int width = img.getWidth();
								((File)destinationFile).setWidth(width);
							}
						} catch (Exception ioe) {
							Logger.error(this.getClass(), ioe.getMessage(), ioe);
						}

					}
				} else {
					fileAssetCont.setInode(null);
					fileAssetCont.setFolder(parent.getInode());
					fileAssetCont.setBinary(FileAssetAPI.BINARY_FIELD, fileData);
					fileAssetCont.setLanguageId(defaultLang);
					fileAssetCont = APILocator.getContentletAPI().checkin(fileAssetCont, user, false);
					if(isAutoPub && perAPI.doesUserHavePermission(fileAssetCont, PermissionAPI.PERMISSION_PUBLISH, user))
					    APILocator.getContentletAPI().publish(fileAssetCont, user, false);
				}

				//Wiping out the thumbnails and resized versions
				//http://jira.dotmarketing.net/browse/DOTCMS-5911
				APILocator.getFileAssetAPI().cleanThumbnailsFromFileAsset(destinationFile);
			}
		}
	}

    /**
     * Create temporal user folder and create a file inside of it
     *
     * @param fieldVar
     * @param userId
     * @param fileName
     * @return created file
     */
	private java.io.File createFileInTemporalFolder(Field fieldVar, final String userId, final String fileName) {
        final String folderPath = new StringBuilder()
                .append(APILocator.getFileAPI().getRealAssetPathTmpBinary())
                .append(java.io.File.separator).append(userId).append(java.io.File.separator)
                .append(fieldVar.getFieldContentlet()).toString();

        java.io.File tempUserFolder = new java.io.File(folderPath);
        if (!tempUserFolder.exists())
            tempUserFolder.mkdirs();

        final String filePath = new StringBuilder()
        .append(tempUserFolder.getAbsolutePath())
        .append(java.io.File.separator).append(fileName).toString();

        java.io.File fileData = new java.io.File(filePath);
        if(fileData.exists())
            fileData.delete();

        return fileData;
	}

	public Folder createFolder(String folderUri, User user) throws IOException, DotDataException {
		Folder folder = null;
		folderUri = stripMapping(folderUri);
		PermissionAPI perAPI = APILocator.getPermissionAPI();
		Logger.debug(this.getClass(), "createFolder");
		String hostName = getHostname(folderUri);
		String path = getPath(folderUri);

		Host host;
		try {
			host = hostAPI.findByName(hostName, user, false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}

		// CheckPermission
		List<Permission> parentPermissions = new ArrayList<Permission>();
		boolean hasPermission = false;
		boolean validName = true;
		String parentPath = getFolderName(path);
		if (UtilMethods.isSet(parentPath) && !parentPath.equals("/")) {
			Folder parentFolder;
			try {
				parentFolder = folderAPI.findFolderByPath(parentPath,host,user,false);
				hasPermission = perAPI.doesUserHavePermission(parentFolder,	PERMISSION_CAN_ADD_CHILDREN, user, false);
			} catch (Exception e) {
				Logger.error(DotWebdavHelper.class,e.getMessage(),e);
				throw new IOException(e.getMessage());
			}
		} else {
			if (host != null && InodeUtils.isSet(host.getInode())) {
				java.util.List<String> reservedFolderNames = new java.util.ArrayList<String>();
				String[] reservedFolderNamesArray = Config.getStringArrayProperty("RESERVEDFOLDERNAMES");
				for (String name : reservedFolderNamesArray) {
					reservedFolderNames.add(name.toUpperCase());
				}
				validName = (!(reservedFolderNames.contains(path.substring(1).toUpperCase())));
			}
			try {
				hasPermission = perAPI.doesUserHavePermission(host, PERMISSION_CAN_ADD_CHILDREN, user, false);
			} catch (DotDataException e) {
				Logger.error(DotWebdavHelper.class,e.getMessage(),e);
				throw new IOException(e.getMessage());
			}
		}

		// Create the new folders with it parent permissions
		if ((hasPermission) && (validName)) {
			if (InodeUtils.isSet(host.getInode())) {
				path = deleteSpecialCharacter(path);
				try {
					folder = folderAPI.createFolders(path, host,user,false);
				} catch (Exception e) {
					throw new DotDataException(e.getMessage(), e);
				}
			}
		}
		return folder;
	}

	public void move(String fromPath, String toPath, User user,boolean autoPublish)throws IOException, DotDataException {
		fromPath = stripMapping(fromPath);
		toPath = stripMapping(toPath);
		PermissionAPI perAPI = APILocator.getPermissionAPI();

		String hostName = getHostname(fromPath);
		String toParentPath = getFolderName(getPath(toPath));

		Host host;
		Folder toParentFolder;
		try {
			host = hostAPI.findByName(hostName, user, false);
		    toParentFolder = folderAPI.findFolderByPath(toParentPath,host,user,false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}
		if (isResource(fromPath,user)) {
			try {
				if (!perAPI.doesUserHavePermission(toParentFolder,
						PermissionAPI.PERMISSION_READ, user, false)) {
					throw new IOException("User doesn't have permissions to move file to folder");
				}
			} catch (DotDataException e1) {
				Logger.error(DotWebdavHelper.class,e1.getMessage(),e1);
				throw new IOException(e1.getMessage());
			}
			if (toParentFolder == null || !InodeUtils.isSet(toParentFolder.getInode())) {
				throw new IOException("Cannot move a file to the root of the host.");
			}

			try{
				Identifier identifier  = APILocator.getIdentifierAPI().find(host, getPath(fromPath));

				Identifier identTo  = APILocator.getIdentifierAPI().find(host, getPath(toPath));
				boolean destinationExists=identTo!=null && InodeUtils.isSet(identTo.getId());

				if(identifier!=null && identifier.getAssetType().equals("contentlet")){
					Contentlet fileAssetCont = APILocator.getContentletAPI().findContentletByIdentifier(identifier.getId(), false, defaultLang, user, false);
					if(!destinationExists) {
    					if (getFolderName(fromPath).equals(getFolderName(toPath))) {
    						String fileName = getFileName(toPath);
    						if(fileName.contains(".")){
    							fileName = fileName.substring(0, fileName.lastIndexOf("."));
    						}
    						APILocator.getFileAssetAPI().renameFile(fileAssetCont, fileName, user, false);
    					} else {
    						APILocator.getFileAssetAPI().moveFile(fileAssetCont, toParentFolder, user, false);
    					}
					}
					else {
					    // if the destination exists lets just create a new version and delete the original file
					    Contentlet origin = APILocator.getContentletAPI().findContentletByIdentifier(identifier.getId(), false, defaultLang, user, false);
					    Contentlet toContentlet = APILocator.getContentletAPI().findContentletByIdentifier(identTo.getId(), false, defaultLang, user, false);
					    Contentlet newversion = APILocator.getContentletAPI().checkout(toContentlet.getInode(), user, false);

					    // get a copy in a tmp folder to avoid filename change
					    java.io.File tmpDir=new java.io.File(APILocator.getFileAPI().getRealAssetPathTmpBinary()
                                +java.io.File.separator+UUIDGenerator.generateUuid());
					    java.io.File tmp=new java.io.File(tmpDir, toContentlet.getBinary(FileAssetAPI.BINARY_FIELD).getName());
					    FileUtil.copyFile(origin.getBinary(FileAssetAPI.BINARY_FIELD), tmp);

					    newversion.setBinary(FileAssetAPI.BINARY_FIELD, tmp);
					    newversion.setLanguageId(defaultLang);
					    newversion = APILocator.getContentletAPI().checkin(newversion, user, false);
					    if(autoPublish) {
					        APILocator.getContentletAPI().publish(newversion, user, false);
					    }

					    APILocator.getContentletAPI().unlock(newversion, user, false);

					    APILocator.getContentletAPI().delete(origin, APILocator.getUserAPI().getSystemUser(), false);
					    while(APILocator.getContentletAPI().isInodeIndexed(origin.getInode(),1));
					}
				}else{
					File f = fileAPI.getFileByURI(getPath(fromPath), host, false, user, false);
					if (getFolderName(fromPath).equals(getFolderName(toPath))) {

						String fileName = getFileName(toPath);
						if(fileName.contains(".")){
							fileName = fileName.substring(0, fileName.lastIndexOf("."));
						}
						fileAPI.renameFile(f, fileName, user, false);

					} else {
						fileAPI.moveFile(f, toParentFolder, user, false);
					}
					if (autoPublish && perAPI.doesUserHavePermission(f, PermissionAPI.PERMISSION_PUBLISH, user)) {

						PublishFactory.publishAsset(f, user, false);

					}
					APILocator.getFileAPI().invalidateCache(f);
					CacheLocator.getIdentifierCache().removeFromCacheByVersionable(f);
					LiveCache.removeAssetFromCache(f);
					WorkingCache.removeAssetFromCache(f);
				}

			}catch (Exception e) {
				throw new DotDataException(e.getMessage(),e);
			}
		} else {
			if (UtilMethods.isSet(toParentPath) && !toParentPath.equals("/")) {
				try {
					if (!perAPI.doesUserHavePermission(toParentFolder,	PermissionAPI.PERMISSION_READ, user, false)) {
						throw new IOException("User doesn't have permissions to move file to folder");
					}
				} catch (DotDataException e1) {
					Logger.error(DotWebdavHelper.class,e1.getMessage(),e1);
					throw new IOException(e1.getMessage());
				}
				if (getFolderName(fromPath).equals(getFolderName(toPath))) {
					Logger.debug(this, "Calling Folderfactory to rename " + fromPath + " to " + toPath);
					try{
					    // Folder must end with "/", otherwise we get the parent folder
                        String folderToPath = getPath(toPath);
                        if(!folderToPath.endsWith("/")) { folderToPath = folderToPath + "/"; }

                        Folder folder = folderAPI.findFolderByPath(folderToPath, host, user, false);
                        removeObject(toPath, user);
                        fc.removeFolder(folder, idapi.find(folder));
					}catch (Exception e) {
						Logger.debug(this, "Unable to delete toPath " + toPath);
					}
					boolean renamed = false;
					try{
						Folder folder = folderAPI.findFolderByPath(getPath(fromPath), host,user,false);
						renamed = folderAPI.renameFolder(folder, getFileName(toPath),user,false);
						fc.removeFolder(folder,idapi.find(folder));
						//folderAPI.updateMovedFolderAssets(folder);
					}catch (Exception e) {
						throw new DotDataException(e.getMessage(), e);
					}
					if(!renamed){
						Logger.error(this, "Unable to remame folder");
						throw new IOException("Unable to rename folder");
					}
				} else {
					Logger.debug(this, "Calling folder factory to move from " + fromPath + " to " + toParentPath);
					Folder fromFolder;
					try {
						fromFolder = folderAPI.findFolderByPath(getPath(fromPath), host,user,false);
					} catch (Exception e1) {
						Logger.error(DotWebdavHelper.class, e1.getMessage(), e1);
						throw new DotRuntimeException(e1.getMessage(), e1);
					}
					if(fromFolder != null){
						Logger.debug(this, "Calling folder factory to move from " + idapi.find(fromFolder).getPath() + " to " + toParentPath);
						Logger.debug(this, "the from folder inode is " + fromFolder.getInode());
					}else{
						Logger.debug(this, "The from folder is null");
					}
					try {
						folderAPI.move(fromFolder, toParentFolder,user,false);
						fc.removeFolder(fromFolder,idapi.find(fromFolder));
						fc.removeFolder(toParentFolder,idapi.find(toParentFolder));
						//folderAPI.updateMovedFolderAssets(fromFolder);
					} catch (Exception e) {
						Logger.error(DotWebdavHelper.class, e.getMessage(), e);
						throw new DotDataException(e.getMessage(), e);
					}
				}
			} else {
				try {
					if (!perAPI.doesUserHavePermission(host,PermissionAPI.PERMISSION_READ, user, false)) {
						throw new IOException("User doesn't have permissions to move file to host");
					}
				} catch (DotDataException e) {
					Logger.error(DotWebdavHelper.class,e.getMessage(),e);
					throw new IOException(e.getMessage());
				}
				if (getFolderName(fromPath).equals(getFolderName(toPath))) {
					try{
						Folder fromfolder = folderAPI.findFolderByPath(getPath(fromPath), host,user,false);
						folderAPI.renameFolder(fromfolder, getFileName(toPath),user,false);
						fc.removeFolder(fromfolder,idapi.find(fromfolder));
					}catch (Exception e) {
						throw new DotDataException(e.getMessage(), e);
					}
				} else {
					Folder fromFolder;
					try {
						fromFolder = folderAPI.findFolderByPath(getPath(fromPath), host,user,false);
						folderAPI.move(fromFolder, host,user,false);
						fc.removeFolder(fromFolder,idapi.find(fromFolder));
						//folderAPI.updateMovedFolderAssets(fromFolder);
					} catch (Exception e) {
						Logger.error(DotWebdavHelper.class, e.getMessage(), e);
						throw new DotDataException(e.getMessage(), e);
					}
				}
			}
		}

	}

	public void removeObject(String uri, User user) throws IOException, DotDataException, DotSecurityException {
		uri = stripMapping(uri);
		Logger.debug(this.getClass(), "In the removeObject Method");
		String hostName = getHostname(uri);
		String path = getPath(uri);
		String folderName = getFolderName(path);
		Host host;
		WebAsset webAsset=null;
		try {
			host = hostAPI.findByName(hostName, user, false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}
		Folder folder = folderAPI.findFolderByPath(folderName, host,user,false);
		if (isResource(uri,user)) {
			Identifier identifier  = APILocator.getIdentifierAPI().find(host, path);

			Long timeOfPublishing = fileResourceCache.get(uri + "|" + user.getUserId());
			Date currentDate = new Date();
			long diff = -1;
			long minTimeAllowed = Config.getIntProperty("WEBDAV_MIN_TIME_AFTER_PUBLISH_TO_ALLOW_DELETING_OF_FILES", 10);
			boolean canDelete= true;

			if(UtilMethods.isSet(timeOfPublishing)) {
				diff = (currentDate.getTime()-timeOfPublishing)/1000;
				canDelete = diff >= minTimeAllowed;
			}

			if(identifier!=null && identifier.getAssetType().equals("contentlet")){
			    Contentlet fileAssetCont = APILocator.getContentletAPI()
			    		.findContentletByIdentifier(identifier.getId(), false, defaultLang, user, false);

			    //Webdav calls the delete method when is creating a new file. But it creates the file with 0 content length.
			    //No need to wait 10 seconds with files with 0 length.
			    if(canDelete
			    		|| (fileAssetCont.getBinary(FileAssetAPI.BINARY_FIELD) != null
			    			&& fileAssetCont.getBinary(FileAssetAPI.BINARY_FIELD).length() <= 0)){

			    	try{
				        APILocator.getContentletAPI().archive(fileAssetCont, user, false);
				    }catch (Exception e) {
				        Logger.error(DotWebdavHelper.class, e.getMessage(), e);
				        throw new DotDataException(e.getMessage(), e);
				    }

				    WorkingCache.removeAssetFromCache(fileAssetCont);
				    LiveCache.removeAssetFromCache(fileAssetCont);
				    fileResourceCache.remove(uri + "|" + user.getUserId());
			    }
			} else {
			    webAsset = fileAPI.getFileByURI(path, host, false, user, false);
			    // This line just archive the assets
			    // WebAssetFactory.deleteAsset(file, user.getUserId());
			    // This line delete the assets (no archive)
			    try{
			        WebAssetFactory.archiveAsset(webAsset, user);
			    }catch (Exception e) {
			        Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			        throw new DotDataException(e.getMessage(), e);
			    }
			    WorkingCache.removeAssetFromCache(webAsset);
			    LiveCache.removeAssetFromCache(webAsset);
			}

		} else if (isFolder(uri,user)) {
			if(!path.endsWith("/"))
				path += "/";
			folder = folderAPI.findFolderByPath(path, host,user,false);
			if (folder.isShowOnMenu()) {
				// RefreshMenus.deleteMenus();
				RefreshMenus.deleteMenu(folder);
				CacheLocator.getNavToolCache().removeNav(folder.getHostId(), folder.getInode());
				if(!path.equals("/")) {
				    Identifier ii=APILocator.getIdentifierAPI().find(folder);
				    CacheLocator.getNavToolCache().removeNavByPath(ii.getHostId(), ii.getParentPath());
				}
			}

			folderAPI.delete(folder, user,false);

		}


	}
	private static Map<String, LockToken> locks = new HashMap<String, LockToken>();
//	private static LockToken currentLock;

	public final LockResult lock(LockTimeout lockTimeout, LockInfo lockInfo, String uid)
	  {
//	    Logger.debug("Lock : " + lockTimeout + " info : " + lockInfo + " on resource : " + getName() + " in : " + parent);
	    LockToken token = new LockToken();
	    token.info = lockInfo;
	    token.timeout = LockTimeout.parseTimeout("30");
	    token.tokenId = uid;
	    locks.put(uid, token);
//	    LockToken currentLock = token;
	    return LockResult.success(token);
	  }

	  public final LockResult refreshLock(String uid)
	  {
//	    log.trace("RefreshLock : " + tokenId + " on resource : " + getName() + " in : " + parent);
	    //throw new UnsupportedOperationException("Not supported yet.");
	    LockToken token = new LockToken();
	    token.info = null;
	    token.timeout = LockTimeout.parseTimeout("30");
	    token.tokenId = uid;
	    locks.put(uid, token);
	    return LockResult.success(token);
	  }

	  public void unlock(String uid)
	  {
//	    log.trace("UnLock : " + arg0 + " on resource : " + getName() + " in : " + parent);
		 locks.remove(uid);
	    //throw new UnsupportedOperationException("Not supported yet.");
	  }

	  public final LockToken getCurrentLock(String uid)
	  {
//	    log.trace("GetCurrentLock");
	    return locks.get(uid);
	  }

//	public LockResult lock(com.dotcms.repackage.com.bradmcevoy.http.LockInfo lock, User user, String uniqueId) {
//		LockToken lt = new LockToken();
////		lock.depth = com.dotcms.repackage.com.bradmcevoy.http.LockInfo.LockDepth.INFINITY;
////		if(authType.equals(Company.AUTH_TYPE_EA))
////			lock.owner = user.getEmailAddress();
////		else
////			lock.owner = user.getUserId();
////		lock.scope = com.dotcms.repackage.com.bradmcevoy.http.LockInfo.LockScope.NONE;
////		lock.type = com.dotcms.repackage.com.bradmcevoy.http.LockInfo.LockType.WRITE;
//
//		// Generating lock id
//		String lockTokenStr = lock.owner + "-" + uniqueId;
//		String lockToken = md5Encoder.encode(md5Helper.digest(lockTokenStr.getBytes()));
//
//		resourceLocks.put(lockToken, lock);
//		lt.tokenId = lockToken;
//		lt.info = lock;
//
//		lt.timeout = new LockTimeout(new Long(45));
//		return new LockResult(null, lt);
//	}
//
//	public LockToken getLockToken(String lockId){
//		com.dotcms.repackage.com.bradmcevoy.http.LockInfo info = resourceLocks.get(lockId);
//		if(info == null)
//			return null;
//		LockToken lt = new LockToken();
//		lt.info = info;
//		lt.timeout = new LockTimeout(new Long(45));
//		lt.tokenId = lockId;
//		return lt;
//	}
//
//	public LockResult refreshLock(String lockId){
//		com.dotcms.repackage.com.bradmcevoy.http.LockInfo info = resourceLocks.get(lockId);
//		if(info == null)
//			return null;
//		LockToken lt = new LockToken();
//		lt.info = info;
//		lt.timeout = new LockTimeout(new Long(45));
//		lt.tokenId = lockId;
//		return new LockResult(null, lt);
//	}
//
//	public void unlock(String lockId){
//		resourceLocks.remove(lockId);
//	}

	private String getFileName(String uri) {
		int begin = uri.lastIndexOf("/") + 1;
		int end = uri.length();
		String fileName = uri.substring(begin, end);
		return fileName;
	}

	private String getFolderName(String uri) {
		if (uri.endsWith("/")) {
			uri = uri.substring(0, uri.length() - 1);
		}
		int begin = 0;
		int end = uri.lastIndexOf("/") + 1;
		String folderName = uri.substring(begin, end);
		return folderName;
	}

	private String getHostname(String uri) {
		if (uri == null || uri.equals("")) {
			return "/";
		}
		int begin = 1;
		int end = (uri.indexOf("/", 1) != -1 ? uri.indexOf("/", 1) : uri.length());
		uri = uri.substring(begin, end);
		return uri;
	}

	private String getPath(String uri) {
		int begin = (uri.indexOf("/", 1) != -1 ? uri.indexOf("/", 1) : uri.length());
		int end = uri.length();
		uri = uri.substring(begin, end);
		return uri;
	}
	
	public long getLanguage(){
		return defaultLang;
	}
	
	/**
	 * This method takes the path and strips all strings that are related to the endpoint. 
	 * Also, if the new pathing is used when it's stripping it, set it as defaultLang, so it can be used by the other methods.
	 * 
	 * e.g: uri = /webdav/live/2/demo.dotcms.com/home -> defaultLang set to 2 and returns /demo.dotcms.com/home (after stripping)
	 * 
	 * @param uri Full URL of the connection
	 * @return the URL without the endpoint
	 * @throws IOException when the language passed in the path doesn't exist the IOException will be thrown.
	 * 
	 */
	public String stripMapping(String uri) throws IOException {
		String r = uri;
		if (r.startsWith("/webdav")) {
			r = r.substring(7, r.length());
		}
		if(legacyPath){
			if (r.startsWith("/nonpub")) {
				r = r.substring(7, r.length());
			}
			if (r.startsWith("/autopub")) {
				r = r.substring(8, r.length());
			}
		}else{
			if (r.startsWith("/working")) {
				r = r.substring(8, r.length());
			}
			if (r.startsWith("/live")) {
				r = r.substring(5, r.length());
			}
			if(StringUtils.isNumeric(r.substring(1, 2))){
			defaultLang = Long.parseLong(r.substring(1, 2));
				if(!APILocator.getLanguageAPI().getLanguages().contains(APILocator.getLanguageAPI().getLanguage(defaultLang))){
					throw new IOException("The language id specified in the path does not exists");
				}
				r = r.substring(2);
			}
		}
		return r;
	}

	private String deleteSpecialCharacter(String fileName) throws IOException {
		if (UtilMethods.isSet(fileName)) {
			fileName = fileName.replace("\\", "");
			fileName = fileName.replace(":", "");
			fileName = fileName.replace("*", "");
			fileName = fileName.replace("?", "");
			fileName = fileName.replace("\"", "");
			fileName = fileName.replace("<", "");
			fileName = fileName.replace(">", "");
			fileName = fileName.replace("|", "");
			if (!UtilMethods.isSet(fileName)) {
				throw new IOException(
						"Please specify a name without special characters \\/:*?\"<>|");
			}
		}
		return fileName;

	}

	private boolean checkFolderFilter(Folder folder, String fileName) {
		boolean returnValue = false;
		returnValue = folderAPI.matchFilter(folder, fileName);
		return returnValue;
	}

	private Summary[] getChildrenData(String folderUriAux, User user) throws IOException {
		PermissionAPI perAPI = APILocator.getPermissionAPI();
		Logger.debug(this.getClass(), "getChildrenNames");
		folderUriAux=stripMapping(folderUriAux);
		ArrayList<Summary> returnValue = new ArrayList<Summary>();
		try {
			// ### GET THE HOST ###
			if (folderUriAux.equals("") || folderUriAux.equals("/")) {
				List<Host> hosts = hostAPI.findAll(user, false);
				for (Host host : hosts) {
					Summary s = new Summary();
					s.setName(host.getHostname());
					s.setPath("/" + s.getName());
					s.setFolder(true);
					s.setCreateDate(host.getModDate());
					s.setModifyDate(host.getModDate());
					s.setHost(host);
					returnValue.add(s);
				}
			} else {
				// ### GET THE FOLDERS AT THE FIRST LEVEL ###
				String hostName = getHostname(folderUriAux);
				Host host;
				try {
					host = hostAPI.findByName(hostName, user, false);
				} catch (DotDataException e) {
					Logger.error(DotWebdavHelper.class, e.getMessage(), e);
					throw new IOException(e.getMessage());
				} catch (DotSecurityException e) {
					Logger.error(DotWebdavHelper.class, e.getMessage(), e);
					throw new IOException(e.getMessage());
				}
				String path = getPath(folderUriAux);
				if (path.equals("") || path.equals("/")) {
					List<Folder> folders = folderAPI.findSubFolders(host,user,false);
					for (Folder folderAux : folders) {
						if (perAPI.doesUserHavePermission(folderAux, PERMISSION_READ, user, false)) {
							Summary s = new Summary();
							s.setName(folderAux.getName());
							s.setPath("/" + host.getHostname()
									+ idapi.find(folderAux).getPath());
							s.setPath(s.getPath().substring(0,
									s.getPath().length() - 1));
							s.setFolder(true);
							s.setCreateDate(folderAux.getIDate());
							s.setCreateDate(folderAux.getModDate());
							s.setHost(host);
							returnValue.add(s);
						}
					}
				} else {
					// ### GET THE FOLDERS, HTMLPAHES AND FILES AT SECOND LEVEL
					// AND LOWERS ###
					path += "/";
					Folder folder = folderAPI.findFolderByPath(path, host, user, false);
					if (InodeUtils.isSet(folder.getInode())) {
						List<Folder> folders = new ArrayList<Folder>();
						// List<HTMLPage> pages = new ArrayList<HTMLPage>();
						List<Versionable> files = new ArrayList<Versionable>();
						// List<Link> links = new ArrayList<Link>();

						try {
							folders = (ArrayList<Folder>)APILocator.getFolderAPI().findSubFolders(folder, user, false);
							// pages = (ArrayList<HTMLPage>)
							// InodeFactory.getChildrenClassByCondition(folder,HTMLPage.class,
							// conditionAsset);
							files.addAll(folderAPI.getWorkingFiles(folder, user,false));
							if(folder.getInode().equals(FolderAPI.SYSTEM_FOLDER)){
								files.addAll(APILocator.getFileAssetAPI().findFileAssetsByHost(APILocator.getHostAPI().find(folder.getHostId(), user,false), user,false));
							}else{
								files.addAll(APILocator.getFileAssetAPI().findFileAssetsByFolder(folder, user,false));
							}
							// links = (ArrayList<Link>)
							// InodeFactory.getChildrenClassByCondition(folder,Link.class,
							// conditionAsset);
						} catch (Exception ex) {
							String message = ex.getMessage();
							Logger.debug(this, ex.toString());
						}

						for (Folder folderAux : folders) {
							if (perAPI.doesUserHavePermission(folderAux,
									PERMISSION_READ, user, false)) {
								Summary s = new Summary();
								s.setFolder(true);
								s.setCreateDate(folderAux.getiDate());
								s.setModifyDate(folderAux.getModDate());
								s.setName(folderAux.getName());
								s.setPath("/" + host.getHostname()
										+ idapi.find(folderAux).getPath());
								s.setPath(s.getPath().substring(0,
										s.getPath().length() - 1));
								s.setHost(host);
								returnValue.add(s);
							}
						}

						for (Versionable file : files) {
							if (perAPI.doesUserHavePermission((Permissionable)file,
									PERMISSION_READ, user, false)) {
								IFileAsset fa = (IFileAsset)file;
								String fileUri = "";
								java.io.File workingFile = null;
								FileInputStream is = null;
								Date idate = null;
								if(file instanceof Contentlet){
									Identifier identifier  = APILocator.getIdentifierAPI().find((Contentlet) file);
									if(identifier!=null && identifier.getAssetType().equals("contentlet")){
										fileUri = identifier.getPath();
										workingFile = ((Contentlet)file).getBinary(FileAssetAPI.BINARY_FIELD);
										is = new FileInputStream(workingFile);
										idate = ((Contentlet)file).getModDate();
									}
								}else if(file instanceof File){
									fileUri = ((File) file).getURI();
								    workingFile = fileAPI.getAssetIOFile((File)file);
								    is = new FileInputStream(workingFile);
								    idate = ((File)file).getiDate();
								}
								int begin = fileUri.lastIndexOf("/") + 1;
								int end = fileUri.length();
								fileUri = fileUri.substring(begin, end);
								Summary s = new Summary();
								s.setFolder(false);
								s.setName(fileUri);
								s.setPath(s.getName());
								s.setPath(folderUriAux + "/" + fileUri);
								s.setCreateDate(idate);
								s.setModifyDate(fa.getModDate());

								s.setLength(is.available());
								s.setHost(host);
								s.setFile(fa);
								returnValue.add(s);
							}
						}

					}
				}
			}
		} catch (Exception ex) {
			Logger.debug(this, ex.toString());
		}
		return returnValue.toArray(new Summary[returnValue.size()]);
	}
	private InputStream getResourceContent(String resourceUri, User user) throws Exception {
		resourceUri=stripMapping(resourceUri);
		Logger.debug(this.getClass(), "getResourceContent");
		InputStream returnValue = null;
		String hostName = getHostname(resourceUri);
		String path = getPath(resourceUri);
		String folderName = getFolderName(path);
		Host host;
		Folder folder;
		try {
			host = hostAPI.findByName(hostName, user, false);
			folder = folderAPI.findFolderByPath(folderName, host,user,false);
		} catch (DotDataException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		} catch (DotSecurityException e) {
			Logger.error(DotWebdavHelper.class, e.getMessage(), e);
			throw new IOException(e.getMessage());
		}
		if (host != null && InodeUtils.isSet(host.getInode()) && InodeUtils.isSet(folder.getInode())) {
			java.io.File workingFile  = null;
			Identifier identifier  = APILocator.getIdentifierAPI().find(host, path);
			if(identifier!=null && identifier.getAssetType().equals("contentlet")){
                Contentlet cont  = APILocator.getContentletAPI().findContentletByIdentifier(identifier.getId(), false, defaultLang, user, false);
			    workingFile = cont.getBinary(FileAssetAPI.BINARY_FIELD);
			}else{
				File file = fileAPI.getFileByURI(path, host, false, user, false);
				// inode{1}/inode{2}/inode.file_extension
				workingFile = fileAPI.getAssetIOFile(file);
			}
			FileInputStream is = new FileInputStream(workingFile);
			returnValue = is;
		}
		return returnValue;
	}
	private void setResourceContent(String resourceUri, InputStream content,	String contentType, String characterEncoding, User user) throws Exception {
		try {
			setResourceContent(resourceUri, content, contentType, characterEncoding, Calendar.getInstance().getTime(), user, false);
		} catch (Exception e) {
			Logger.error(this,e.getMessage(), e);
			throw new DotRuntimeException(e.getMessage(),e);
		}
	}

	public static FileResourceCache getFileResourceCache() {
		return fileResourceCache;
	}

}
