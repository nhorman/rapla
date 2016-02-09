package org.rapla.server.internal;

import org.rapla.RaplaResources;
import org.rapla.components.util.ParseDateException;
import org.rapla.components.util.SerializableDateTimeFormat;
import org.rapla.components.xmlbundle.I18nBundle;
import org.rapla.entities.DependencyException;
import org.rapla.entities.Entity;
import org.rapla.entities.EntityNotFoundException;
import org.rapla.entities.RaplaType;
import org.rapla.entities.User;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.domain.internal.AppointmentImpl;
import org.rapla.entities.domain.internal.ReservationImpl;
import org.rapla.entities.dynamictype.Classifiable;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.internal.DynamicTypeImpl;
import org.rapla.entities.storage.EntityReferencer;
import org.rapla.entities.storage.ReferenceInfo;
import org.rapla.facade.Conflict;
import org.rapla.facade.internal.ConflictImpl;
import org.rapla.framework.RaplaException;
import org.rapla.framework.internal.ContainerImpl;
import org.rapla.framework.logger.Logger;
import org.rapla.inject.DefaultImplementation;
import org.rapla.inject.InjectionContext;
import org.rapla.jsonrpc.common.FutureResult;
import org.rapla.jsonrpc.common.ResultImpl;
import org.rapla.jsonrpc.common.VoidResult;
import org.rapla.plugin.mail.MailPlugin;
import org.rapla.plugin.mail.server.MailInterface;
import org.rapla.server.AuthenticationStore;
import org.rapla.server.RemoteSession;
import org.rapla.storage.CachableStorageOperator;
import org.rapla.storage.PermissionController;
import org.rapla.storage.PreferencePatch;
import org.rapla.storage.RaplaNewVersionException;
import org.rapla.storage.RaplaSecurityException;
import org.rapla.storage.StorageOperator;
import org.rapla.storage.UpdateEvent;
import org.rapla.storage.dbrm.AppointmentMap;
import org.rapla.storage.dbrm.RemoteStorage;
import org.rapla.storage.impl.EntityStore;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@DefaultImplementation(of =RemoteStorage.class,context = InjectionContext.server)
public class RemoteStorageImpl implements RemoteStorage
{
    private final RemoteSession session;
    private final CachableStorageOperator operator;
    private final SecurityManager security;
    private final ShutdownService shutdownService;

    private final Set<AuthenticationStore> authenticationStore;

    private final RaplaResources i18n;
    private final Provider<MailInterface> mailInterface;
    private final UpdateDataManager updateDataManager;

    @Inject public RemoteStorageImpl(RemoteSession session, CachableStorageOperator operator, SecurityManager security, ShutdownService shutdownService,
            Set<AuthenticationStore> authenticationStore, RaplaResources i18n, Provider<MailInterface> mailInterface, UpdateDataManager updateDataManager)
    {
        this.session = session;
        this.updateDataManager = updateDataManager;
        this.operator = operator;
        this.security = security;
        this.authenticationStore = authenticationStore;
        this.shutdownService = shutdownService;
        this.mailInterface = mailInterface;
        this.i18n = i18n;
    }

    public FutureResult<UpdateEvent> getResources()
    {
        try
        {
            checkAuthentified();
            User user = getSessionUser();
            getLogger().debug("A RemoteAuthentificationService wants to get all resource-objects.");
            Date serverTime = operator.getCurrentTimestamp();
            Collection<Entity> visibleEntities = operator.getVisibleEntities(user);
            UpdateEvent evt = new UpdateEvent();
            evt.setUserId(user.getId());
            for (Entity entity : visibleEntities)
            {
                if (UpdateDataManagerImpl.isTransferedToClient(entity))
                {
                    if (entity instanceof Preferences)
                    {
                        Preferences preferences = (Preferences) entity;
                        ReferenceInfo<User> ownerId = preferences.getOwnerRef();
                        if (ownerId == null && !user.isAdmin())
                        {
                            entity = UpdateDataManagerImpl.removeServerOnlyPreferences(preferences);
                        }
                    }
                    evt.putStore(entity);
                }
            }
            evt.setLastValidated(serverTime);
            return new ResultImpl<UpdateEvent>(evt);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<UpdateEvent>(ex);
        }
    }

    public FutureResult<UpdateEvent> getEntityRecursive(UpdateEvent.SerializableReferenceInfo... ids)
    {
        try
        {
            checkAuthentified();
            Date repositoryVersion = operator.getCurrentTimestamp();
            User sessionUser = getSessionUser();

            ArrayList<Entity> completeList = new ArrayList<Entity>();
            for (UpdateEvent.SerializableReferenceInfo id : ids)
            {
                final ReferenceInfo reference = id.getReference();
                Entity entity = operator.resolve(reference);
                if (entity instanceof Classifiable)
                {
                    if (!DynamicTypeImpl.isTransferedToClient((Classifiable) entity))
                    {
                        throw new RaplaSecurityException("Entity for id " + id + " is not transferable to the client");
                    }
                }
                if (entity instanceof DynamicType)
                {
                    if (!DynamicTypeImpl.isTransferedToClient((DynamicType) entity))
                    {
                        throw new RaplaSecurityException("Entity for id " + id + " is not transferable to the client");
                    }
                }
                if (entity instanceof Reservation)
                {
                    entity = checkAndMakeReservationsAnonymous(sessionUser, entity);
                }
                if (entity instanceof Preferences)
                {
                    entity = UpdateDataManagerImpl.removeServerOnlyPreferences((Preferences) entity);
                }
                security.checkRead(sessionUser, entity);
                completeList.add(entity);
                getLogger().debug("Get entity " + entity);
            }
            UpdateEvent evt = new UpdateEvent();
            evt.setLastValidated(repositoryVersion);
            for (Entity entity : completeList)
            {
                evt.putStore(entity);
            }
            return new ResultImpl<UpdateEvent>(evt);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<UpdateEvent>(ex);
        }
    }


    @Override
    public FutureResult<AppointmentMap> queryAppointments(String[] allocatableIds, Date start, Date end, Map<String, String> annotationQuery)
    {
        getLogger().debug("A RemoteAuthentificationService wants to reservations from ." + start + " to " + end);
        try
        {
            checkAuthentified();
            User sessionUser = getSessionUser();
            User user = null;
            // Reservations and appointments
            List<Allocatable> allocatables = new ArrayList<Allocatable>();
            if (allocatableIds != null)
            {
                for (String id : allocatableIds)
                {
                    Allocatable allocatable = operator.resolve(id, Allocatable.class);
                    security.checkRead(sessionUser, allocatable);
                    allocatables.add(allocatable);
                }
            }
            ClassificationFilter[] classificationFilters = null;
            FutureResult<Map<Allocatable, Collection<Appointment>>> reservationsQuery = operator
                    .queryAppointments(user, allocatables, start, end, classificationFilters, annotationQuery);
            Map<Allocatable, Collection<Appointment>> reservations = reservationsQuery.get();
            AppointmentMap list = new AppointmentMap(reservations);
            getLogger().debug("Get reservations " + start + " " + end + ": " + reservations.size() + "," + list.toString());
            return new ResultImpl<AppointmentMap>(list);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<AppointmentMap>(ex);
        }
        catch (Exception ex)
        {
            getLogger().error(ex.getMessage(), ex);
            return new ResultImpl<AppointmentMap>(ex);
        }
    }

    private ReservationImpl checkAndMakeReservationsAnonymous(User sessionUser, Entity entity)
    {
        ReservationImpl reservation = (ReservationImpl) entity;
        PermissionController permissionController = operator.getPermissionController();
        boolean reservationVisible = permissionController.canRead(reservation, sessionUser);
        // check if the user is allowed to read the reservation info
        if (!reservationVisible)
        {
            ReservationImpl clone = reservation.clone();
            // we can safely change the reservation info here because we cloned it in transaction safe before
            DynamicType anonymousReservationType = operator.getDynamicType(StorageOperator.ANONYMOUSEVENT_TYPE);
            clone.setClassification(anonymousReservationType.newClassification());
            clone.setReadOnly();
            return clone;
        }
        else
        {
            return reservation;
        }
    }

    protected boolean isAllocatablesVisible(User sessionUser, Reservation res)
    {
        ReferenceInfo<User> ownerId = res.getOwnerRef();
        if (sessionUser.isAdmin() || ownerId == null || ownerId.equals(sessionUser.getId()))
        {
            return true;
        }
        PermissionController permissionController = operator.getPermissionController();
        for (Allocatable allocatable : res.getAllocatables())
        {
            if (permissionController.canRead(allocatable, sessionUser))
            {
                return true;
            }
        }
        return true;
    }

    public FutureResult<VoidResult> restartServer()
    {
        try
        {
            checkAuthentified();
            if (!getSessionUser().isAdmin())
                throw new RaplaSecurityException("Only admins can restart the server");

            shutdownService.shutdown(true);
            return ResultImpl.VOID;
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<VoidResult>(ex);
        }
    }

    public FutureResult<UpdateEvent> dispatch(UpdateEvent event)
    {
        try
        {
            checkAuthentified();
            Date currentTimestamp = operator.getCurrentTimestamp();
            Date lastSynced = event.getLastValidated();
            if (lastSynced == null)
            {
                throw new RaplaException("client sync time is missing");
            }
            if (lastSynced.after(currentTimestamp))
            {
                long diff = lastSynced.getTime() - currentTimestamp.getTime();
                getLogger().warn("Timestamp of client " + diff + " ms  after server ");
                lastSynced = currentTimestamp;
            }
            //   LocalCache cache = operator.getCache();
            //   UpdateEvent event = createUpdateEvent( context,xml, cache );
            User sessionUser = getSessionUser();
            getLogger().info("Dispatching change for user " + sessionUser);
            if (sessionUser != null)
            {
                event.setUserId(sessionUser.getId());
            }
            dispatch_(event);
            getLogger().info("Change for user " + sessionUser + " dispatched.");

            UpdateEvent result = updateDataManager.createUpdateEvent(sessionUser, lastSynced);
            return new ResultImpl<UpdateEvent>(result);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<UpdateEvent>(ex);
        }
    }

    public FutureResult<String> canChangePassword()
    {
        try
        {
            checkAuthentified();
            Boolean result = operator.canChangePassword();
            return new ResultImpl<String>(result.toString());
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<String>(ex);
        }
    }

    public FutureResult<VoidResult> changePassword(String username, String oldPassword, String newPassword)
    {
        try
        {
            checkAuthentified();
            User sessionUser = getSessionUser();

            if (!sessionUser.isAdmin())
            {
                if ( authenticationStore != null && authenticationStore.size() > 0 )
                {
                    throw new RaplaSecurityException("Rapla can't change your password. Authentication handled by ldap plugin.");
                }
                operator.authenticate(username, new String(oldPassword));
            }
            User user = operator.getUser(username);
            operator.changePassword(user, oldPassword.toCharArray(), newPassword.toCharArray());
            return ResultImpl.VOID;
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<VoidResult>(ex);
        }

    }

    public FutureResult<VoidResult> changeName(String username, String newTitle, String newSurename, String newLastname)
    {
        try
        {
            checkAuthentified();
            User changingUser = getSessionUser();
            User user = operator.getUser(username);
            if (changingUser.isAdmin() || user.equals(changingUser))
            {
                operator.changeName(user, newTitle, newSurename, newLastname);
            }
            else
            {
                throw new RaplaSecurityException("Not allowed to change email from other users");
            }
            return ResultImpl.VOID;
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<VoidResult>(ex);
        }
    }

    public FutureResult<VoidResult> changeEmail(String username, String newEmail)

    {
        try
        {
            checkAuthentified();
            User changingUser = getSessionUser();
            User user = operator.getUser(username);
            if (changingUser.isAdmin() || user.equals(changingUser))
            {
                operator.changeEmail(user, newEmail);
            }
            else
            {
                throw new RaplaSecurityException("Not allowed to change email from other users");
            }
            return ResultImpl.VOID;
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<VoidResult>(ex);
        }
    }

    public FutureResult<String> getUsername(String userId)
    {
        try
        {
            checkAuthentified();
            String username = operator.getUsername(new ReferenceInfo<User>(userId,User.class));
            return new ResultImpl<String>(username);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<String>(ex);
        }
    }

    public FutureResult<VoidResult> confirmEmail(String username, String newEmail)
    {
        try
        {
            checkAuthentified();
            User changingUser = getSessionUser();
            User user = operator.getUser(username);
            if (changingUser.isAdmin() || user.equals(changingUser))
            {
                String subject = getString("security_code");
                Preferences prefs = operator.getPreferences(null, true);
                String mailbody =
                        "" + getString("send_code_mail_body_1") + user.getUsername() + ",\n\n" + getString("send_code_mail_body_2") + "\n\n" + getString(
                                "security_code") + Math.abs(user.getEmail().hashCode()) + "\n\n" + getString("send_code_mail_body_3") + "\n\n"
                                + "-----------------------------------------------------------------------------------" + "\n\n" + getString(
                                "send_code_mail_body_4") + prefs.getEntryAsString(ContainerImpl.TITLE, getString("rapla.title")) + " " + getString(
                                "send_code_mail_body_5");

                final MailInterface mail = mailInterface.get();
                final String defaultSender = prefs.getEntryAsString(MailPlugin.DEFAULT_SENDER_ENTRY, "");

                mail.sendMail(defaultSender, newEmail, subject, "" + mailbody);
            }
            else
            {
                throw new RaplaSecurityException("Not allowed to change email from other users");
            }
            return ResultImpl.VOID;
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<VoidResult>(ex);
        }
    }

    private String getString(String key)
    {
        return getI18n().getString(key);
    }

    public I18nBundle getI18n()
    {
        return i18n;
    }

    public FutureResult<List<String>> createIdentifier(String type, int count)
    {
        try
        {
            checkAuthentified();
            Class<? extends Entity> typeClass = RaplaType.find(type);
            //User user =
            getSessionUser(); //check if authenified
            ReferenceInfo[] refs = operator.createIdentifier(typeClass, count);
            List<String> result = new ArrayList<String>();
            for ( ReferenceInfo ref:refs)
            {
                result.add( ref.getId());
            }
            return new ResultImpl<List<String>>(result);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<List<String>>(ex);
        }
    }

    public FutureResult<UpdateEvent> refresh(String lastSyncedTime)
    {
        try
        {
            checkAuthentified();
            Date clientRepoVersion = SerializableDateTimeFormat.INSTANCE.parseTimestamp(lastSyncedTime);
            UpdateEvent event = updateDataManager.createUpdateEvent(getSessionUser(), clientRepoVersion);
            return new ResultImpl<UpdateEvent>(event);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<UpdateEvent>(ex);
        }
        catch (ParseDateException e)
        {
            return new ResultImpl<UpdateEvent>(new RaplaException(e.getMessage()));
        }
    }

    public Logger getLogger()
    {
        return session.getLogger();
    }

    private void checkAuthentified() throws RaplaSecurityException
    {
        if (!session.isAuthentified())
        {

            throw new RaplaSecurityException(RemoteStorage.USER_WAS_NOT_AUTHENTIFIED);
        }
    }

    private User getSessionUser() throws RaplaException
    {
        return session.getUser();
    }

    private void dispatch_(UpdateEvent evt) throws RaplaException
    {
        try
        {
            User user;
            if (evt.getUserId() != null)
            {
                user = operator.resolve(evt.getUserId(), User.class);
            }
            else
            {
                user = getSessionUser();
            }
            Collection<Entity> storeObjects = evt.getStoreObjects();
            EntityStore store = new EntityStore(operator);
            store.addAll(storeObjects);
            for (EntityReferencer references : evt.getEntityReferences())
            {
                references.setResolver(store);
            }
            for (Entity entity : storeObjects)
            {
                security.checkWritePermissions(user, entity);
            }
            List<PreferencePatch> preferencePatches = evt.getPreferencePatches();
            for (PreferencePatch patch : preferencePatches)
            {
                security.checkWritePermissions(user, patch);
            }
            Collection<ReferenceInfo> removeObjects = evt.getRemoveIds();
            for (ReferenceInfo id : removeObjects)
            {
                Entity entity = operator.tryResolve(id);
                if (entity != null)
                {
                    security.checkDeletePermissions(user, entity);
                }
            }
            if (this.getLogger().isDebugEnabled())
                this.getLogger().debug("Dispatching changes to " + operator.getClass());

            operator.dispatch(evt);
            if (this.getLogger().isDebugEnabled())
                this.getLogger().debug("Changes dispatched returning result.");
        }
        catch (DependencyException ex)
        {
            throw ex;
        }
        catch (RaplaNewVersionException ex)
        {
            throw ex;
        }
        catch (RaplaSecurityException ex)
        {
            this.getLogger().warn(ex.getMessage());
            throw ex;
        }
        catch (RaplaException ex)
        {
            this.getLogger().error(ex.getMessage(), ex);
            throw ex;
        }
        catch (Exception ex)
        {
            this.getLogger().error(ex.getMessage(), ex);
            throw new RaplaException(ex);
        }
        catch (Error ex)
        {
            this.getLogger().error(ex.getMessage(), ex);
            throw ex;
        }
    }

    public FutureResult<List<ConflictImpl>> getConflicts()
    {
        try
        {
            checkAuthentified();
            Set<Entity> completeList = new HashSet<Entity>();
            User sessionUser = getSessionUser();
            Collection<Conflict> conflicts = operator.getConflicts(sessionUser);
            List<ConflictImpl> result = new ArrayList<ConflictImpl>();
            for (Conflict conflict : conflicts)
            {
                result.add((ConflictImpl) conflict);
                Entity conflictRef = conflict;
                completeList.add(conflictRef);
            }
            return new ResultImpl<List<ConflictImpl>>(result);
        }
        catch (RaplaException ex)
        {
            return new ResultImpl<List<ConflictImpl>>(ex);
        }
    }

    @Override public FutureResult<Date> getNextAllocatableDate(String[] allocatableIds, AppointmentImpl appointment, String[] reservationIds,
            Integer worktimestartMinutes, Integer worktimeendMinutes, Integer[] excludedDays, Integer rowsPerHour)
    {
        try
        {
            checkAuthentified();
            List<Allocatable> allocatables = resolveAllocatables(allocatableIds);
            Collection<Reservation> ignoreList = resolveReservations(reservationIds);
            Date result = operator
                    .getNextAllocatableDate(allocatables, appointment, ignoreList, worktimestartMinutes, worktimeendMinutes, excludedDays, rowsPerHour)
                    .get();
            return new ResultImpl<Date>(result);
        }
        catch (Exception ex)
        {
            return new ResultImpl<Date>(ex);
        }
    }

    @Override public FutureResult<BindingMap> getFirstAllocatableBindings(String[] allocatableIds, List<AppointmentImpl> appointments,
            String[] reservationIds)
    {
        try
        {
            checkAuthentified();
            //Integer[][] result = new Integer[allocatableIds.length][];
            List<Allocatable> allocatables = resolveAllocatables(allocatableIds);
            Collection<Reservation> ignoreList = resolveReservations(reservationIds);
            List<Appointment> asList = cast(appointments);
            Map<Allocatable, Collection<Appointment>> bindings = operator.getFirstAllocatableBindings(allocatables, asList, ignoreList).get();
            Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
            for (Allocatable alloc : bindings.keySet())
            {
                Collection<Appointment> apps = bindings.get(alloc);
                if (apps == null)
                {
                    apps = Collections.emptyList();
                }
                ArrayList<String> indexArray = new ArrayList<String>(apps.size());
                for (Appointment app : apps)
                {
                    for (Appointment app2 : appointments)
                    {
                        if (app2.equals(app))
                        {
                            indexArray.add(app.getId());
                        }
                    }
                }
                result.put(alloc.getId(), indexArray);
            }
            return new ResultImpl<BindingMap>(new BindingMap(result));
        }
        catch (Exception ex)
        {
            return new ResultImpl<BindingMap>(ex);
        }
    }

    private List<Appointment> cast(List<AppointmentImpl> appointments)
    {
        List<Appointment> result = new ArrayList<Appointment>(appointments.size());
        for (Appointment app : appointments)
        {
            result.add(app);
        }
        return result;
    }

    public FutureResult<List<ReservationImpl>> getAllAllocatableBindings(String[] allocatableIds, List<AppointmentImpl> appointments,
            String[] reservationIds)
    {
        try
        {
            checkAuthentified();
            Set<ReservationImpl> result = new HashSet<ReservationImpl>();
            List<Allocatable> allocatables = resolveAllocatables(allocatableIds);
            Collection<Reservation> ignoreList = resolveReservations(reservationIds);
            List<Appointment> asList = cast(appointments);
            Map<Allocatable, Map<Appointment, Collection<Appointment>>> bindings = operator.getAllAllocatableBindings(allocatables, asList, ignoreList)
                    .get();
            for (Allocatable alloc : bindings.keySet())
            {
                Map<Appointment, Collection<Appointment>> appointmentBindings = bindings.get(alloc);
                for (Appointment app : appointmentBindings.keySet())
                {
                    Collection<Appointment> bound = appointmentBindings.get(app);
                    if (bound != null)
                    {
                        for (Appointment appointment : bound)
                        {
                            ReservationImpl reservation = (ReservationImpl) appointment.getReservation();
                            if (reservation != null)
                            {
                                result.add(reservation);
                            }
                        }
                    }
                }
            }
            return new ResultImpl<List<ReservationImpl>>(new ArrayList<ReservationImpl>(result));
        }
        catch (Exception ex)
        {
            return new ResultImpl<List<ReservationImpl>>(ex);
        }
    }

    private List<Allocatable> resolveAllocatables(String[] allocatableIds) throws RaplaException, RaplaSecurityException
    {
        List<Allocatable> allocatables = new ArrayList<Allocatable>();
        User sessionUser = getSessionUser();
        for (String id : allocatableIds)
        {
            Allocatable entity = operator.resolve(id, Allocatable.class);
            allocatables.add(entity);
            security.checkRead(sessionUser, entity);
        }
        return allocatables;
    }

    private Collection<Reservation> resolveReservations(String[] ignoreList)
    {
        Set<Reservation> ignoreConflictsWith = new HashSet<Reservation>();
        for (String reservationId : ignoreList)
        {
            try
            {
                Reservation entity = operator.resolve(reservationId, Reservation.class);
                ignoreConflictsWith.add(entity);
            }
            catch (EntityNotFoundException ex)
            {
                // Do nothing reservation not found and assumed new
            }
        }
        return ignoreConflictsWith;
    }

    //			public void logEntityNotFound(String logMessage,String... referencedIds)
    //			{
    //				StringBuilder buf = new StringBuilder();
    //				buf.append("{");
    //				for  (String id: referencedIds)
    //				{
    //					buf.append("{ id=");
    //					if ( id != null)
    //					{
    //						buf.append(id.toString());
    //						buf.append(": ");
    //						Entity refEntity = operator.tryResolve(id);
    //						if ( refEntity != null )
    //						{
    //							buf.append( refEntity.toString());
    //						}
    //						else
    //						{
    //							buf.append("NOT FOUND");
    //						}
    //					}
    //					else
    //					{
    //						buf.append( "is null");
    //					}
    //
    //					buf.append("},  ");
    //				}
    //				buf.append("}");
    //				getLogger().error("EntityNotFoundFoundExceptionOnClient "+ logMessage + " " + buf.toString());
    //				//return ResultImpl.VOID;
    //			}
}
