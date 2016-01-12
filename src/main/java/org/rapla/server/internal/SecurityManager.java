/*--------------------------------------------------------------------------*
 | Copyright (C) 2006 Praktikum Gruppe2?, Christopher Kohlhaas              |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla.server.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.rapla.RaplaResources;
import org.rapla.entities.Annotatable;
import org.rapla.entities.Category;
import org.rapla.entities.Entity;
import org.rapla.entities.Ownable;
import org.rapla.entities.RaplaType;
import org.rapla.entities.User;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.AppointmentFormater;
import org.rapla.entities.domain.Permission;
import org.rapla.entities.domain.PermissionContainer;
import org.rapla.entities.domain.Reservation;
import org.rapla.storage.PermissionController;
import org.rapla.entities.dynamictype.Classifiable;
import org.rapla.facade.ClientFacade;
import org.rapla.facade.Conflict;
import org.rapla.framework.RaplaException;
import org.rapla.framework.logger.Logger;
import org.rapla.storage.PreferencePatch;
import org.rapla.storage.RaplaSecurityException;
import org.rapla.storage.StorageOperator;

/** checks if the client can store or delete an entity */
@Singleton
public class SecurityManager 
{
    final RaplaResources i18n;
    final AppointmentFormater appointmentFormater;
    final StorageOperator operator;
    final Logger logger;
    final ClientFacade facade;
    private final PermissionController permissionController;

    @Inject
    public SecurityManager(Logger logger, RaplaResources i18n, AppointmentFormater appointmentFormater, ClientFacade facade)
    {
        this.logger = logger;
        this.i18n = i18n;
        this.appointmentFormater = appointmentFormater;
        this.facade = facade;
        operator = facade.getOperator();
        permissionController = operator.getPermissionController();
    }
    
    void checkWritePermissions(User user,Entity entity, boolean admin) throws RaplaSecurityException {
        if (user.isAdmin())
            return;

        Object id = entity.getId();
        if (id == null)
            throw new RaplaSecurityException("No id set");

        boolean permitted = false;
        @SuppressWarnings("unchecked")
        Class<Entity> typeClass = entity.getRaplaType().getTypeClass();
        Entity original = operator.tryResolve( entity.getId(), typeClass);
        // flag indicates if a user only exchanges allocatables  (needs to have admin-access on the allocatable)
        boolean canExchange = false;

        boolean ownable = entity instanceof Ownable;
		if (ownable || entity instanceof Appointment) {
            String entityOwnerId;
            if ( ownable)
            {
            	entityOwnerId = ((Ownable) entity).getOwnerId();
            }
            else
            {
            	entityOwnerId = ((Appointment) entity).getOwnerId();
            }
            if (original == null) {
                permitted = entityOwnerId != null && user.getId().equals(entityOwnerId);
                if (getLogger().isDebugEnabled())
                {
                    getLogger().debug("Permissions for new object " + entity + "\nUser check: " + user + " = " + operator.tryResolve(entityOwnerId, User.class));
                }
            } else {
            	String originalOwnerId;
                if ( ownable)
                {
                	originalOwnerId = ((Ownable) original).getOwnerId();
                }
                else
                {
                	originalOwnerId = ((Appointment) original).getOwnerId();
                }

                if (getLogger().isDebugEnabled())
                {
                    final User entityOwner = operator.tryResolve(entityOwnerId, User.class);
                    final User originalOwner = operator.tryResolve(originalOwnerId, User.class);
                    getLogger().debug("Permissions for existing object " + entity + "\nUser check: " + user + " = " + entityOwner + " = " + originalOwner);
                }
                permitted = (originalOwnerId != null) && originalOwnerId.equals(user.getId()) && originalOwnerId.equals(entityOwnerId);
                if ( !permitted && !admin) {
                	canExchange = canExchange( user, entity, original );
                	permitted = canExchange;
                }
            }
        } 
        if ( permitted && entity instanceof Classifiable ){
            if ( original == null ) {
                permitted = permissionController.canCreate((Classifiable)entity, user);
            } 
        }
        if ( !permitted && original != null && original instanceof PermissionContainer)
        {
            if ( admin)
            {
                permitted = permissionController.canAdmin(original, user);
            }
            else
            {
                permitted = permissionController.canModify(original, user);
            }
            
        }
        if (!permitted && entity instanceof Appointment)
        {
            final Reservation reservation = ((Appointment)entity).getReservation();
            Reservation originalReservation = operator.tryResolve(reservation.getId(), Reservation.class);
            if ( originalReservation != null)
            {
            	permitted = permissionController.canModify(originalReservation, user);
            }
        }
        
        if (!permitted && entity instanceof Conflict)
        {
            Conflict conflict = (Conflict) entity;
            if (permissionController.canModify(conflict, user))
            {
                permitted = true;
            }
        }

        if (!permitted && entity instanceof Annotatable)
        {
            permitted = permissionController.canWriteTemplate(( Annotatable)entity, user );
        }
        
        if (!permitted)
        {
            String errorText;
            if (admin)
            {
                errorText = i18n.format("error.admin_not_allowed", new Object[] { user.toString(), entity.toString() });
            }
            else
            {
                errorText = i18n.format("error.modify_not_allowed", new Object[] { user.toString(), entity.toString() });
            }
            throw new RaplaSecurityException(errorText);
            
        }

        // Check if the user can change the reservation
        if ( Reservation.TYPE ==entity.getRaplaType() )
        {
            Reservation reservation = (Reservation) entity ;
            Reservation originalReservation = (Reservation)original;
            Allocatable[] all = reservation.getAllocatables();
            if ( originalReservation != null && canExchange ) {
                List<Allocatable> newAllocatabes = new ArrayList<Allocatable>( Arrays.asList(reservation.getAllocatables() ) );
                newAllocatabes.removeAll( Arrays.asList( originalReservation.getAllocatables()));
                all = newAllocatabes.toArray( Allocatable.ALLOCATABLE_ARRAY);
            }
            if ( originalReservation == null)
            {
                boolean canCreate = permissionController.canCreate(reservation, user);
                //Category group = getUserGroupsCategory().getCategory( Permission.GROUP_CAN_CREATE_EVENTS);
            	if (!canCreate)
            	{
            		throw new RaplaSecurityException(i18n.format("error.create_not_allowed", new Object []{ user.toString(),entity.toString()}));
            	} 
            }
            checkPermissions( user, reservation, originalReservation , all);
        }
        
        // FIXME check if permissions are changed and user has admin priviliges 

    }

    private Logger getLogger() 
    {
        return logger;
    }

//    protected boolean isRegisterer(User user) throws RaplaSecurityException {
//        try {
//            Category registererGroup = getUserGroupsCategory().getCategory(Permission.GROUP_REGISTERER_KEY);
//            return user.belongsTo(registererGroup);
//        } catch (RaplaException ex) {
//            throw new RaplaSecurityException(ex );
//        }
//    }

    public Category getUserGroupsCategory() throws RaplaSecurityException {
        Category userGroups = operator.getSuperCategory().getCategory(Permission.GROUP_CATEGORY_KEY);
        if ( userGroups == null) {
            throw new RaplaSecurityException("No category '" + Permission.GROUP_CATEGORY_KEY + "' available");
        }
        return userGroups;
    }


    /** checks if the user just exchanges one allocatable or removes one. The user needs admin-access on the
     * removed allocatable and the newly inserted allocatable */
    private boolean canExchange(User user,Entity entity,Entity original) {
        if ( Appointment.TYPE.equals( entity.getRaplaType() )) {
            return ((Appointment) entity).matches( (Appointment) original );
        } if ( Reservation.TYPE.equals( entity.getRaplaType() )) {
            Reservation newReservation = (Reservation) entity;
            Reservation oldReservation = (Reservation) original;
            // We only need to check the length because we compare the appointments above.
            if ( newReservation.getAppointments().length != oldReservation.getAppointments().length )
            {
                return false;
            }

            List<Allocatable> oldAllocatables = Arrays.asList(oldReservation.getAllocatables());
            List<Allocatable> newAllocatables = Arrays.asList(newReservation.getAllocatables());
            List<Allocatable> inserted = new ArrayList<Allocatable>(newAllocatables);
            List<Allocatable> removed = new ArrayList<Allocatable>(oldAllocatables);
            List<Allocatable> overlap = new ArrayList<Allocatable>(oldAllocatables);
            inserted.removeAll( oldAllocatables );
            removed.removeAll( newAllocatables );
            overlap.retainAll( inserted );
            if ( inserted.size() == 0 && removed.size() == 0)
            {
            	return false;
            }
            //  he must have admin rights on all inserted resources
            Iterator<Allocatable> it = inserted.iterator();
            while (it.hasNext()) {
                if (!canAllocateForOthers(it.next(),user))
                {
                    return false;
                }
            }

            //   and  he must have admin rights on all the removed resources
            it = removed.iterator();
            while (it.hasNext()) {
                if (!canAllocateForOthers(it.next(),user))
                {
                    return false;
                }
            }

            // He can't change appointments, only exchange allocatables he has admin-priviliges  for
            it = overlap.iterator();
            while (it.hasNext()) {
                Allocatable all = it.next();
                Appointment[] r1 = newReservation.getRestriction( all );
                Appointment[] r2 = oldReservation.getRestriction( all );
                boolean changed = false;
                if ( r1.length != r2.length ) {
                    changed = true;
                } else {
                    for ( int i=0; i< r1.length; i++ ) {
                        if ( !r1[i].matches(r2[i]) ) {
                            changed = true;
                        }
                    }
                }
                if ( changed && !canAllocateForOthers( all, user )) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** for Thierry, we can make this configurable in the next version */
    private boolean canAllocateForOthers(Allocatable allocatable, User user) {
        // only admins, current behaviour
        return permissionController.canModify(allocatable, user);
        // everyone who can allocate the resource anytime
        //return allocatable.canAllocate( user, null, null, operator.today());
        // everyone
        //return true;
    }

    private void checkConflictsAllowed(User user, Allocatable allocatable, Conflict[] conflictsBefore, Conflict[] conflictsAfter) throws RaplaSecurityException {
        int nConflictsBefore = 0;
        int nConflictsAfter = 0;
        if ( permissionController.canCreateConflicts( allocatable, user ) ) {
            return;
        }
        if ( conflictsBefore != null ) {
            for ( int i = 0; i < conflictsBefore.length; i++ ) {
                if ( conflictsBefore[i].getAllocatable().equals ( allocatable ) ) {
                    nConflictsBefore ++;
                }
            }
        }

        for ( int i = 0; i < conflictsAfter.length; i++ ) {
            if ( conflictsAfter[i].getAllocatable().equals ( allocatable ) ) {
                nConflictsAfter ++;
            }
        }
        if ( nConflictsAfter > nConflictsBefore ) {
            String all = allocatable.getName( i18n.getLocale() );
            throw new RaplaSecurityException( i18n.format("warning.no_conflict_permission", all ) );
        }
    }

    private void checkPermissions( User user, Reservation r, Reservation original, Allocatable[] allocatables ) throws RaplaSecurityException {
    	Conflict[] conflictsBefore = null;
        Conflict[] conflictsAfter = null;
        try {
            conflictsAfter = facade.getConflicts(  r );
            if ( original != null ) {
                conflictsBefore = facade.getConflicts(  original );
            }
        } catch ( RaplaException ex ) {
            throw new RaplaSecurityException(" Can't check permissions due to:" + ex.getMessage(), ex );
        }

        Appointment[] appointments = r.getAppointments();
        // ceck if the user has the permisson to add allocations in the given time
        for (int i = 0; i < allocatables.length; i++ ) {
            Allocatable allocatable = allocatables[i];
            checkConflictsAllowed( user, allocatable, conflictsBefore, conflictsAfter );
            for (int j = 0; j < appointments.length; j++ ) {
                Appointment appointment = appointments[j];
                Date today = operator.today();
				if ( r.hasAllocated( allocatable, appointment ) &&
                     !permissionController.hasPermissionToAllocate( user, appointment, allocatable, original,today ) ) {
                    String all = allocatable.getName( i18n.getLocale() );
                    String app = appointmentFormater.getSummary( appointment );
                    String error = i18n.format("warning.no_reserve_permission"
                                               ,all
                                               ,app);
                    throw new RaplaSecurityException( error );
                }
            }
        }
        if (original == null )
            return;

        Date today = operator.today();

        // 1. calculate the deleted assignments from allocatable to appointments
        // 2. check if they were allowed to change in the specified time
        appointments = original.getAppointments();
        allocatables = original.getAllocatables();
        for (int i = 0; i < allocatables.length; i++ ) {
            Allocatable allocatable = allocatables[i];
            for (int j = 0; j < appointments.length; j++ ) {
                Appointment appointment = appointments[j];
                if ( original.hasAllocated( allocatable, appointment )
                     && !r.hasAllocated( allocatable, appointment ) ) {
                    Date start = appointment.getStart();
                    Date end = appointment.getMaxEnd();
                    if ( !permissionController.canAllocate( allocatable, user, start, end, today ) ) {
                        String all = allocatable.getName( i18n.getLocale() );
                        String app = appointmentFormater.getSummary( appointment );
                        String error = i18n.format("warning.no_reserve_permission"
                                                   ,all
                                                   ,app);
                        throw new RaplaSecurityException( error );
                    }
                }
            }
        }
    }
    
    public void checkRead(User user,Entity entity) throws RaplaSecurityException, RaplaException {
		RaplaType<?> raplaType = entity.getRaplaType();
		if ( raplaType == Allocatable.TYPE)
		{
		    Allocatable allocatable = (Allocatable) entity;
			if ( !permissionController.canReadOnlyInformation( allocatable, user))
			{
				throw new RaplaSecurityException(i18n.format("error.read_not_allowed",user, allocatable.getName( null)));
			}
		}
		if ( raplaType == Preferences.TYPE)
		{
		    Ownable ownable = (Preferences) entity;
		    String ownerId = ownable.getOwnerId();
		    if (  user != null && !user.isAdmin() && (ownerId == null || !user.getId().equals( ownerId)))
			{
				throw new RaplaSecurityException(i18n.format("error.read_not_allowed", user, entity));
			}
		}
		
	}

    public void checkWritePermissions(User user, PreferencePatch patch) throws RaplaSecurityException 
    {

        String ownerId = patch.getUserId();
        if (  user != null && !user.isAdmin() && (ownerId == null || !user.getId().equals( ownerId)))
        {
            throw new RaplaSecurityException("User " + user + " can't modify preferences " + ownerId);
        }
        
    }
    
}