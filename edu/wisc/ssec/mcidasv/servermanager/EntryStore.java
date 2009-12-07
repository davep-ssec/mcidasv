/*
 * $Id$
 *
 * This file is part of McIDAS-V
 *
 * Copyright 2007-2009
 * Space Science and Engineering Center (SSEC)
 * University of Wisconsin - Madison
 * 1225 W. Dayton Street, Madison, WI 53706, USA
 * http://www.ssec.wisc.edu/mcidas
 * 
 * All Rights Reserved
 * 
 * McIDAS-V is built on Unidata's IDV and SSEC's VisAD libraries, and
 * some McIDAS-V source code is based on IDV and VisAD source code.  
 * 
 * McIDAS-V is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * McIDAS-V is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */
package edu.wisc.ssec.mcidasv.servermanager;

import static edu.wisc.ssec.mcidasv.util.CollectionHelpers.concurrentMap;
import static edu.wisc.ssec.mcidasv.util.CollectionHelpers.newLinkedHashSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.w3c.dom.Element;

import ucar.unidata.idv.IdvResourceManager;
import ucar.unidata.idv.IdvResourceManager.IdvResource;
import ucar.unidata.idv.chooser.adde.AddeServer;
import ucar.unidata.xml.XmlResourceCollection;

import edu.wisc.ssec.mcidasv.Constants;
import edu.wisc.ssec.mcidasv.McIDASV;
import edu.wisc.ssec.mcidasv.ResourceManager;
import edu.wisc.ssec.mcidasv.servermanager.AddeAccount;
import edu.wisc.ssec.mcidasv.servermanager.AddeEntry.EntrySource;
import edu.wisc.ssec.mcidasv.servermanager.AddeEntry.EntryStatus;
import edu.wisc.ssec.mcidasv.servermanager.AddeEntry.EntryType;
import edu.wisc.ssec.mcidasv.servermanager.AddeEntry.EntryValidity;
import edu.wisc.ssec.mcidasv.util.Contract;

public class EntryStore {

    private static final String PREF_ADDE_ENTRIES = "mcv.servers.entries";

    /** The ADDE servers known to McIDAS-V. */
    private final InternalStore entries = new InternalStore();

    /** Object that's running the show. */
    private final McIDASV mcv;

    /** Which port is this particular manager operating on */
    private String localPort = Constants.LOCAL_ADDE_PORT;

    /** Thread that monitors the mcservl process. */
    private AddeThread thread = null;

    public EntryStore(final McIDASV mcv) {
        Contract.notNull(mcv);
        this.mcv = mcv;

        entries.putEntries(extractFromPreferences());
        entries.putEntries(extractUserEntries(ResourceManager.RSC_NEW_USERSERVERS));
        entries.putEntries(extractResourceEntries(EntrySource.SYSTEM, IdvResourceManager.RSC_ADDESERVER));

//        dumbTest();
    }

//    private void dumbTest() {
//        for (String addr : entries.getAddresses()) {
//            boolean addrSeen = false;
//            for (String group : entries.getGroups(addr)) {
//                if (!addrSeen) {
//                    System.err.println(addr+"\t\t"+group+"\t\t"+entries.getTypes(addr, group));
//                    addrSeen = true;
//                } else {
//                    System.err.println("\t\t\t"+group+"\t\t"+entries.getTypes(addr, group));
//                }
//            }
//            System.err.println("------------------------------------");
//        }
//        entries.dumb();
//    }

    // TODO(jon): update this to work with local adde entries.
    public static boolean isInvalidEntry(final AddeEntry e) {
//        return RemoteAddeEntry.INVALID_ENTRY.equals(e);
        return false;
    }

    private Set<AddeEntry> extractFromPreferences() {
        Set<AddeEntry> entries = newLinkedHashSet();

        // this is valid--the only thing ever written to 
        // PREF_REMOTE_ADDE_ENTRIES is an ArrayList of RemoteAddeEntry objects.
        @SuppressWarnings("unchecked")
        List<AddeEntry> asList = 
            (List<AddeEntry>)mcv.getStore().get(PREF_ADDE_ENTRIES);
        if (asList != null)
            entries.addAll(asList);

        return entries;
    }

    /**
     * TODO(jon): this would be handy for starting up the server manager without the rest of mcv
     * @param prefPath
     * @return
     */
//    private Set<AddeEntry> extractFromPreferences(final String prefPath) {
//        Set<AddeEntry> entries = newLinkedHashSet();
//        return entries;
//    }

    /**
     * Saves the current set of remote ADDE servers to the user's preferences.
     */
    public void saveEntries() {
        mcv.getStore().put(PREF_ADDE_ENTRIES, entries.asList());
        mcv.getStore().saveIfNeeded();
    }

    /**
     * TODO(jon): this would be handy for starting up the server manager without the rest of mcv.
     * @param prefPath
     */
//    public void saveEntries(final String prefPath) {
//        
//    }

    /**
     * Returns the {@link Set} of {@link RemoteAddeEntry}s that are known to work (for
     * a given {@link EntryType} of entries).
     * 
     * @param type The {@code EntryType} you are interested in.
     * 
     * @return A {@code Set} of matching {@code RemoteAddeEntry}s. If there 
     * were no matches, an empty {@code Set} is returned.
     */
    public Set<AddeEntry> getVerifiedEntries(final EntryType type) {
        Set<AddeEntry> verified = newLinkedHashSet();
        for (AddeEntry entry : entries.asSet())
            if (entry.getEntryValidity() == EntryValidity.VERIFIED && entry.getEntryType() == type)
                verified.add(entry);
        return verified;
    }

    // TODO(jon): better name
    public Map<EntryType, Set<AddeEntry>> getVerifiedEntriesByTypes() {
        Map<EntryType, Set<AddeEntry>> entryMap = new LinkedHashMap<EntryType, Set<AddeEntry>>();
        for (EntryType type : EntryType.values()) {
            entryMap.put(type, new LinkedHashSet<AddeEntry>());
            //            System.err.println("storing type="+type);
        }

        for (AddeEntry entry : entries.asSet()) {
            Set<AddeEntry> entrySet = entryMap.get(entry.getEntryType());
            entrySet.add(entry);
            //            System.err.println("  boo: "+entry);
            //            if (entry.getEntryValidity() == EntryValidity.VERIFIED) {
            //                Set<RemoteAddeEntry> entrySet = entryMap.get(entry.getEntryType());
            //                entrySet.add(entry);
            //            }
        }
        return entryMap;
    }

    /**
     * Returns the {@link Set} of {@link RemoteAddeEntry#group}s that match
     * the given {@code address} and {@code type}.
     * 
     * @param address
     * @param type
     * 
     * @return Either a set containing the desired groups, or an empty set if
     * there were no matches.
     */
    public Set<String> getGroupsFor(final String address, EntryType type) {
        Set<String> groups = newLinkedHashSet();
        for (AddeEntry entry : entries.asSet())
            if (entry.getAddress().equals(address) && entry.getEntryType() == type)
                groups.add(entry.getGroup());
        return groups;
    }

    /**
     * Returns the {@link Set} of {@link RemoteAddeEntry} addresses stored
     * in this {@code EntryStore}.
     * 
     * @return {@code Set} containing all of the stored addresses. If no 
     * addresses are stored, an empty {@code Set} is returned.
     */
    public Set<String> getAddresses() {
        return entries.getAddresses().getAddresses(); // EEEEWWWWW
    }

    /**
     * Returns the {@link Set} of {@literal "groups"} associated with the 
     * given {@code address}.
     * 
     * @param address Address of a server.
     * 
     * @return Either all of the {@literal "groups"} on {@code address} or an
     * empty {@code Set}.
     */
    public Set<String> getGroups(final String address) {
        return entries.getGroups(address);
    }

    /**
     * Returns the {@link Set} of {@link EntryType}s for a given {@code group}
     * on a given {@code address}.
     * 
     * @param address Address of a server.
     * @param group Group whose {@literal "types"} you want.
     * 
     * @return Either of all the types for a given {@code address} and 
     * {@code group} or an empty {@code Set} if there were no matches.
     */
    public Set<EntryType> getTypes(final String address, final String group) {
        return entries.getAddresses().getGroupsFor(address).getTypesFor(group).getTypes();
    }

    /**
     * Searches the set of servers in an attempt to locate the accounting 
     * information for the matching server. <b>Note</b> that because the data
     * structure is a {@link Set}, there <i>cannot</i> be duplicate entries,
     * so there is no need to worry about our criteria finding multiple 
     * matches.
     * 
     * <p>Also note that none of the given parameters accept {@code null} 
     * values.
     * 
     * @param address Address of the server.
     * @param group Dataset.
     * @param type Group type.
     * 
     * @return Either the {@link AddeAccount} for the given criteria, or 
     * {@link RemoteAddeEntry#DEFAULT_ACCOUNT} if there was no match.
     * 
     * @see RemoteAddeEntry#equals(Object)
     */
    public AddeAccount getAccountingFor(final String address, final String group, EntryType type) {
        AddeEntry e = entries.getAddresses().getGroupsFor(address).getTypesFor(group).getEntryFor(type);
        return (isInvalidEntry(e)) ? RemoteAddeEntry.DEFAULT_ACCOUNT : e.getAccount();
        
    }

    /**
     * Returns the complete {@link Set} of {@link AddeEntry}s.
     * 
     * @return {@link #entries}.
     */
    protected Set<AddeEntry> getEntrySet() {
        return entries.asSet();
    }

    /**
     * Returns the complete {@link Set} of {@link RemoteAddeEntry}s.
     * 
     * @return The {@code RemoteAddeEntry}s stored within {@link #entries}.
     */
    protected Set<RemoteAddeEntry> getRemoteEntries() {
        Set<RemoteAddeEntry> remotes = newLinkedHashSet();
        for (AddeEntry e : entries.asSet()) {
            if (e instanceof RemoteAddeEntry)
                remotes.add((RemoteAddeEntry)e);
        }
        return remotes;
    }

    /**
     * Returns the complete {@link Set} of {@link LocalAddeEntry}s.
     * 
     * @return The {@code LocalAddeEntry}s stored within {@link #entries}.
     */
    protected Set<LocalAddeEntry> getLocalEntries() {
        Set<LocalAddeEntry> locals = newLinkedHashSet();
        for (AddeEntry e : entries.asSet()) {
            if (e instanceof LocalAddeEntry)
                locals.add((LocalAddeEntry)e);
        }
        return locals;
    }

    protected void removeEntry(final AddeEntry entry) {
        if (entry == null)
            throw new NullPointerException("");
        entries.remove(entry);
    }

    /**
     * Adds a {@link Set} of {@link RemoteAddeEntry}s to {@link #entries}.
     * 
     * @param newEntries New entries to add to the server manager. Cannot be
     * {@code null}.
     * 
     * @throws NullPointerException if {@code newEntries} is {@code null}.
     */
    public void addEntries(final Set<? extends AddeEntry> oldEntries, final Set<? extends AddeEntry> newEntries) {
        if (oldEntries == null)
            throw new NullPointerException("Cannot replace a null set");
        if (newEntries == null)
            throw new NullPointerException("Cannot add a null set");

        entries.removeEntries(oldEntries);
        entries.putEntries(newEntries);
    }

    // used for apply/ok?
    public void replaceEntries(final EntryStatus typeToReplace, final Set<RemoteAddeEntry> newEntries) {
    }

    public List<AddeServer> getIdvStyleEntries() {
        return EntryTransforms.convertMcvServers(getEntrySet());
    }

    public List<AddeServer> getIdvStyleEntries(final EntryType type) {
        return EntryTransforms.convertMcvServers(getVerifiedEntries(type));
    }

    public List<AddeServer> getIdvStyleEntries(final String typeAsStr) {
        return getIdvStyleEntries(EntryTransforms.strToEntryType(typeAsStr));
    }

    /**
     * Process all of the {@literal "IDV-style"} XML resources.
     * 
     * @param source
     * @param resource
     * 
     * @return
     */
    private Set<AddeEntry> extractResourceEntries(EntrySource source, final IdvResource resource) {
        Set<AddeEntry> entries = newLinkedHashSet();

        XmlResourceCollection xmlResource = mcv.getResourceManager().getXmlResources(resource);

        for (int i = 0; i < xmlResource.size(); i++) {
            Element root = xmlResource.getRoot(i);
            if (root == null)
                continue;

            Set<AddeEntry> woot = EntryTransforms.convertAddeServerXml(root, source);
            entries.addAll(woot);
        }

        return entries;
    }

    /**
     * Process all of the {@literal "user"} XML resources.
     * 
     * @param resource Resource collection. Cannot be {@code null}.
     * 
     * @return {@link Set} of {@link RemoteAddeEntry}s contained within 
     * {@code resource}.
     */
    private Set<AddeEntry> extractUserEntries(final IdvResource resource) {
        Set<AddeEntry> entries = newLinkedHashSet();

        XmlResourceCollection xmlResource = mcv.getResourceManager().getXmlResources(resource);
        for (int i = 0; i < xmlResource.size(); i++) {
            Element root = xmlResource.getRoot(i);
            if (root == null)
                continue;

            entries.addAll(EntryTransforms.convertUserXml(root));
            //            for (RemoteAddeEntry e : entries) {
            //                System.err.println(e);
            //            }
        }

        return entries;
    }

    /**
     * Change the port we are listening on.
     * 
     * @param port New port number.
     */
    public void setLocalPort(final String port) {
        localPort = port;
    }

    /**
     * Ask for the port we are listening on
     * @return
     */
    public String getLocalPort() {
        return localPort;
    }

    /**
     * Get the next port by incrementing current port
     * @return
     */
    protected String nextLocalPort() {
        return Integer.toString(Integer.parseInt(localPort) + 1);
    }

    /**
     * start addeMcservl if it exists
     */
    public void startLocalServer() {
//        boolean exists = (new File(addeMcservl)).exists();
//        if (exists) {
//            // Create and start the thread if there isn't already one running
//            if (!checkLocalServer()) {
//                thread = new AddeThread(this);
//                thread.start();
//              System.out.println(addeMcservl + " was started on port " + LOCAL_PORT);
//          } else {
//              System.out.println(addeMcservl + " is already running on port " + LOCAL_PORT);
//            }
//        } else {
//            System.err.println(addeMcservl + " does not exist");
//        }
//        setStatus();
    }

    /**
     * stop the thread if it is running
     */
    public void stopLocalServer() {
        if (checkLocalServer()) {
            //TODO: stopProcess (actually Process.destroy()) hangs on Macs...
            //      doesn't seem to kill the children properly
            if (!McIDASV.isMac())
                thread.stopProcess();

            thread.interrupt();
            thread = null;
//          System.out.println(addeMcservl + " was stopped on port " + LOCAL_PORT);
//      } else {
//          System.out.println(addeMcservl + " is not running");
        }
        setStatus();
    }

    /**
     * restart the thread
     */
    public void restartLocalServer() {
        if (checkLocalServer())
            stopLocalServer();

        startLocalServer();
        setStatus();
    }

    /**
     * check to see if the thread is running
     */
    public boolean checkLocalServer() {
        if (thread != null && thread.isAlive()) 
            return true;
        else 
            return false;
    }

    /**
     * update the GUI with the current status
     */
    private void setStatus() {
        String status = new String("Local server is ");
        if (checkLocalServer()) 
            status += "listening on port " + localPort;
        else 
            status += "not running";
//        statusLabel.setText(status);
    }

    private static class InternalStore {
        private final Set<AddeEntry> entrySet = newLinkedHashSet();
        private final Addresses addrs = new Addresses();

        protected InternalStore() {}

        protected boolean contains(final AddeEntry e) {
            return addrs.getGroupsFor(e).getTypesFor(e).getEntryFor(e.getEntryType()).equals(e);
        }

        protected void remove(final AddeEntry e) {
            addrs.removeAddress(e);
        }

        protected void removeEntries(final Set<? extends AddeEntry> es) {
            for (AddeEntry e : es)
                addrs.removeAddress(e);
        }

        protected void putEntries(final Set<? extends AddeEntry> es) {
            for (AddeEntry e : es) 
                putEntry(e);
        }

        protected void putEntry(final AddeEntry e) {
            entrySet.add(e);
            addrs.addAddress(e);
        }

        // TODO(jon): think of a more accurate name!
        protected Addresses getAddresses() {
            return addrs;
        }

        protected Set<String> getGroups(final String address) {
            Set<String> groups = newLinkedHashSet();
            for (String addr : addrs)
                for (String group : addrs.getGroupsFor(addr))
                    groups.add(group);
            return groups;
        }

        protected List<AddeEntry> asList() {
            return new ArrayList<AddeEntry>(entrySet);
        }

        protected Set<AddeEntry> asSet() {
            return new LinkedHashSet<AddeEntry>(entrySet);
        }

        private static class Addresses implements Iterable<String>, Iterator<String> {
            private final Map<String, Groups> addressesToGroups = concurrentMap();
            private volatile Iterator<String> it;

            protected void addAddress(final AddeEntry e) {
                String addr = e.getAddress();
                if (!addressesToGroups.containsKey(addr)) {
                    addressesToGroups.put(addr, new Groups());
                }

                Groups groups = addressesToGroups.get(addr);
                groups.addGroup(e);
            }

            /**
             * Searches for all of the groups associated with {@code addr}.
             * 
             * @param addr Some sort of hostname/IP address.
             * 
             * @return If {@code addr} isn't stored in {@link #addressesToGroups},
             * a blank {@link Groups} object is returned. Otherwise the {@code Groups}
             * object associated with {@code addr} is returned.
             */
            protected Groups getGroupsFor(final String addr) {
                if (!addressesToGroups.containsKey(addr))
                    return new Groups();
                return addressesToGroups.get(addr);
            }

            /**
             * Attempts to remove a given server from the collection. <b>If 
             * there is a match, all group and {@literal "type"} information 
             * will be deleted!</b>
             * 
             * <p>Remember that {@code InternalStore} is a tree--this method is 
             * essentially removing the root node of an {@code InternalStore} 
             * <i>subtree</i>.
             * 
             * @param addr Address to remove.
             * 
             * @return Works like {@link Set#remove(Object)}--{@code true} if
             * there was a {@literal "change,"} {@code false} if nothing happened.
             */
            protected boolean removeAddress(final String addr) {
                // TODO(jon): should these remove methods be more proactive about killing references?
                return (addressesToGroups.remove(addr) != null) ? true : false;
            }

            /**
             * Convenience method for {@link #getGroupsFor(String)}. Helps relieve
             * some of the tedium?
             * 
             * <p>Keep in mind that a {@link AddeEntry} is <i>flat</i>;
             * each entry contains only a single group (and type, etc). This
             * method allows you to get all of the groups associated with 
             * {@code e.getAddress()}.
             * 
             * @param e 
             * 
             * @return
             */
            protected Groups getGroupsFor(final AddeEntry e) {
                return getGroupsFor(e.getAddress());
            }

            /**
             * Convenience method for {@link #removeAddress(String)}.
             * 
             * @param e Entry containing the address to be removed.
             * 
             * @return {@code true} if the remove worked, {@code false} 
             * otherwise.
             * 
             * @see #removeAddress(String)
             */
            protected boolean removeAddress(final AddeEntry e) {
                return removeAddress(e.getAddress());
            }

            /**
             * Returns all of the server addresses stored within this {@literal "tree"}.
             * 
             * @return A {@link Set} containing all of the keys within {@link #addressesToGroups}.
             * 
             * @see #addressesToGroups
             * @see Map#keySet()
             */
            protected Set<String> getAddresses() {
                return new LinkedHashSet<String>(addressesToGroups.keySet());
            }

            // DULL ITERATOR/ITERABLE STUFF AHEAD. RUN AWAY!

            /**
             * Returns a {@link Iterator} capable of iterating over the stored 
             * servers. Allows foreach loops!
             */
            @Override public Iterator<String> iterator() {
                it = addressesToGroups.keySet().iterator();
                return it;
            }

            /**
             * Has {@link #addressesToGroups}'s {@link Map#keySet()} determine
             * whether or not there are any entries to supply. 
             * 
             * @return {@code true} if there is at least one more item, 
             * {@code false} otherwise.
             */
            @Override public boolean hasNext() {
                return (it != null) ? it.hasNext() : false;
            }

            /**
             * Advances the iterator within {@link #addressesToGroups}'s 
             * {@link Map#keySet()}.
             * 
             * @return The {@literal "address"} of some server--unless you try
             * calling {@code next()} *AFTER* {@link #hasNext()} has returned
             * {@code false}.
             * 
             * @throws NoSuchElementException if there are no more elements 
             * to iterate over.
             * 
             * @see #hasNext()
             */
            @Override public String next() {
                if (!it.hasNext())
                    throw new NoSuchElementException();
                return it.next();
            }

            /**
             * Only throws an {@link UnsupportedOperationException}.
             * 
             * @throws UnsupportedOperationException
             */
            @Override public void remove() {
                throw new UnsupportedOperationException();
            }

            // TODO(jon): this forces an iteration through addressesToGroups--reconsider this!
            @Override public String toString() {
                return String.format("[Addresses@%x: addressesToGroups=%s]", hashCode(), addressesToGroups);
            }
        }

        private static class Groups implements Iterable<String>, Iterator<String> {
            private final Map<String, Types> groupsToTypes = concurrentMap();
            private volatile Iterator<String> it;

            protected void addGroup(final AddeEntry e) {
                String group = e.getGroup();
                if (!groupsToTypes.containsKey(group)) {
                    groupsToTypes.put(group, new Types());
                }

                Types types = groupsToTypes.get(group);
                types.addType(e);
            }

            protected Types getTypesFor(final String group) {
                if (!groupsToTypes.containsKey(group))
                    return new Types();
                return groupsToTypes.get(group);
            }

            // acts like a java.util.Set--if the collection changes as a result
            // of the "remove", then return true. Otherwise return false.
            protected boolean removeGroup(final String group) {
                return (groupsToTypes.remove(group) != null) ? true : false;
            }

            protected Types getTypesFor(final AddeEntry e) {
                return getTypesFor(e.getGroup());
            }

            protected Set<String> getGroups() {
                return new LinkedHashSet<String>(groupsToTypes.keySet());
            }

            // DULL ITERATOR/ITERABLE STUFF AHEAD. RUN AWAY!

            /**
             * Returns a {@link Iterator} capable of iterating over the stored 
             * groups. Allows foreach loops!
             */
            @Override public Iterator<String> iterator() {
                it = groupsToTypes.keySet().iterator();
                return it;
            }

            /**
             * Has {@link #groupsToTypes}'s {@link Map#keySet()} determine
             * whether or not there are any entries to supply. 
             * 
             * @return {@code true} if there is at least one more item, 
             * {@code false} otherwise.
             */
            @Override public boolean hasNext() {
                return (it != null) ? it.hasNext() : false;
            }

            /**
             * Advances the iterator within {@link #groupsToTypes}'s 
             * {@link Map#keySet()}.
             * 
             * @return The {@literal "name"} of some group--unless you try
             * calling {@code next()} *AFTER* {@link #hasNext()} has returned
             * {@code false}.
             * 
             * @throws NoSuchElementException if there are no more elements 
             * to iterate over.
             * 
             * @see #hasNext()
             */
            @Override public String next() {
                if (!it.hasNext())
                    throw new NoSuchElementException();
                return it.next();
            }

            /**
             * Only throws an {@link UnsupportedOperationException}.
             * 
             * @throws UnsupportedOperationException
             */
            @Override public void remove() {
                throw new UnsupportedOperationException();
            }

            // TODO(jon): this forces an iteration through addressesToGroups--reconsider this!
            @Override public String toString() {
                return String.format("[Groups@%x: groupsToTypes=%s]", hashCode(), groupsToTypes);
            }
        }

        private static class Types implements Iterable<EntryType>, Iterator<EntryType> {
            private final Map<EntryType, AddeEntry> typesToEntries = concurrentMap();
            private volatile Iterator<EntryType> it;

            protected void addType(final AddeEntry e) {
                EntryType type = e.getEntryType();
                if (!typesToEntries.containsKey(type)) {
                    typesToEntries.put(type, e);
                }
            }

            protected AddeEntry getEntryFor(final EntryType t) {
                if (!typesToEntries.containsKey(t))
                    return RemoteAddeEntry.INVALID_ENTRY;
                return typesToEntries.get(t);
            }

            // acts like a java.util.Set--if the collection changes as a result
            // of the "remove", then return true. Otherwise return false.
            protected boolean removeType(final EntryType t) {
                return (typesToEntries.remove(t) != null) ? true : false;
            }

            /**
             * Convenience method so that you don't have to extract the 
             * {@link EntryType} from an arbitrary {@link AddeEntry}.
             * 
             * <p>Yes, I'm aware that this method seems a bit silly--it's useful
             * for {@link InternalStore#contains(AddeEntry)} though.
             * 
             * @param e 
             * 
             * @return Either the {@code RemoteAddeEntry} associated with {@code e.getEntryType()} or {@code null}.
             */
            protected AddeEntry getEntryFor(final AddeEntry e) {
                return getEntryFor(e.getEntryType());
            }

            protected Set<EntryType> getTypes() {
                return new LinkedHashSet<EntryType>(typesToEntries.keySet());
            }

            // DULL ITERATOR/ITERABLE STUFF AHEAD. RUN AWAY!

            /**
             * Returns a {@link Iterator} capable of iterating over the stored 
             * groups. Allows foreach loops!
             */
            @Override public Iterator<EntryType> iterator() {
                it = typesToEntries.keySet().iterator();
                return it;
            }

            /**
             * Has {@link #typesToEntries}'s {@link Map#keySet()} determine
             * whether or not there are any entries to supply. 
             * 
             * @return {@code true} if there is at least one more item, 
             * {@code false} otherwise.
             */
            @Override public boolean hasNext() {
                return (it != null) ? it.hasNext() : false;
            }

            /**
             * Advances the iterator within {@link #typesToEntries}'s 
             * {@link Map#keySet()}.
             * 
             * @return The {@literal "type"} of some group--unless you try
             * calling {@code next()} *AFTER* {@link #hasNext()} has returned
             * {@code false}.
             * 
             * @throws NoSuchElementException if there are no more elements 
             * to iterate over.
             * 
             * @see #hasNext()
             */
            @Override public EntryType next() {
                if (!it.hasNext())
                    throw new NoSuchElementException();
                return it.next();
            }

            /**
             * Only throws an {@link UnsupportedOperationException}.
             * 
             * @throws UnsupportedOperationException
             */
            @Override public void remove() {
                throw new UnsupportedOperationException();
            }

            @Override public String toString() {
                return String.format("[Types@%x: typesToEntries=%s]", hashCode(), typesToEntries);
            }
        }
    }
}
