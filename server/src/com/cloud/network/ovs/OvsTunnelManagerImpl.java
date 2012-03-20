/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package com.cloud.network.ovs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.ejb.Local;
import javax.naming.ConfigurationException;
import javax.persistence.EntityExistsException;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.manager.Commands;
import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.ovs.dao.OvsTunnelNetworkDao;
import com.cloud.network.ovs.dao.OvsTunnelNetworkVO;
import com.cloud.utils.component.Inject;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;

@Local(value={OvsTunnelManager.class})
public class OvsTunnelManagerImpl implements OvsTunnelManager {
	public static final Logger s_logger = Logger.getLogger(OvsTunnelManagerImpl.class.getName());
	
	String _name;
	boolean _isEnabled;
	ScheduledExecutorService _executorPool;
    ScheduledExecutorService _cleanupExecutor;
    
	@Inject ConfigurationDao _configDao;
	@Inject NicDao _nicDao;
	@Inject HostDao _hostDao;
	@Inject UserVmDao _userVmDao;
	@Inject DomainRouterDao _routerDao;
	@Inject OvsTunnelNetworkDao _tunnelNetworkDao;
	@Inject AgentManager _agentMgr;
	
	@Override
	public boolean configure(String name, Map<String, Object> params)
			throws ConfigurationException {
		_name = name;
		_isEnabled = Boolean.parseBoolean(_configDao.getValue(Config.OvsTunnelNetwork.key()));
		
		if (_isEnabled) {
			_executorPool = Executors.newScheduledThreadPool(10, new NamedThreadFactory("OVS"));
			_cleanupExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("OVS-Cleanup"));
		}
		
		return true;
	}

	@DB
	protected OvsTunnelNetworkVO createTunnelRecord(long from, long to, long networkId, int key) {
		OvsTunnelNetworkVO ta = null;
		
		try {
			ta = new OvsTunnelNetworkVO(from, to, key, networkId);
			OvsTunnelNetworkVO lock = _tunnelNetworkDao.acquireInLockTable(Long.valueOf(1));
			if (lock == null) {
			    s_logger.warn("Cannot lock table ovs_tunnel_account");
			    return null;
			}
			_tunnelNetworkDao.persist(ta);
			_tunnelNetworkDao.releaseFromLockTable(lock.getId());
		} catch (EntityExistsException e) {
			s_logger.debug("A record for the tunnel from " + from + " to " + to + " already exists");
		}
		
		return ta;
	}

	private void handleCreateTunnelAnswer(Answer[] answers){
		OvsCreateTunnelAnswer r = (OvsCreateTunnelAnswer) answers[0];
		String s = String.format(
				"(hostIP:%1$s, remoteIP:%2$s, bridge:%3$s, greKey:%4$s, portName:%5$s)",
				r.getFromIp(), r.getToIp(), r.getBridge(), r.getKey(), r.getInPortName());
		Long from = r.getFrom();
		Long to = r.getTo();
		long networkId = r.getNetworkId();
		OvsTunnelNetworkVO tunnel = _tunnelNetworkDao.getByFromToNetwork(from, to, networkId);
		if (tunnel == null) {
            throw new CloudRuntimeException(String.format("Unable find tunnelNetwork record(from=%1$s, to=%2$s, account=%3$s", from, to, networkId));
		}
		s_logger.debug("Result:" + r.getResult());
		if (!r.getResult()) {
		    tunnel.setState("FAILED");
			s_logger.warn("Create GRE tunnel failed due to " + r.getDetails() + s);
		} else {
		    tunnel.setState("SUCCESS");
		    tunnel.setPortName(r.getInPortName());
		    s_logger.warn("Create GRE tunnel " + r.getDetails() + s);
		}
		_tunnelNetworkDao.update(tunnel.getId(), tunnel);
	}
	
	private int getGreKey(Network network) {
		int key = 0;
		try {
			//The GRE key is actually in the host part of the URI
			String keyStr = network.getBroadcastUri().getHost();
    		// The key is most certainly and int.
    		// So we feel quite safe in converting it into a string			
    		key = Integer.valueOf(keyStr);
    		return key;
		} catch (NumberFormatException e) {
			s_logger.debug("Well well, how did '" + key + 
					       "' end up in the broadcast URI for the network?");
			throw new CloudRuntimeException(
					String.format("Invalid GRE key parsed from network broadcast URI (%s)",
							network.getBroadcastUri().toString()));
		}
	}
	
	@DB
    protected void CheckAndCreateTunnel(VirtualMachine instance, Network nw, DeployDestination dest) {
		if (!_isEnabled) {
			return;
		}
		
		s_logger.debug("Creating tunnels with OVS tunnel manager");
		if (instance.getType() != VirtualMachine.Type.User
				&& instance.getType() != VirtualMachine.Type.DomainRouter) {
			s_logger.debug("Will not work if you're not an instance or a virtual router");
			return;
		}
		
		long hostId = dest.getHost().getId();
		int key = getGreKey(nw);
		// Find active (i.e.: not shut off) VMs with a NIC on the target network
		List<UserVmVO> vms = _userVmDao.listByNetworkIdAndStates(nw.getId(), State.Running, State.Starting,
								State.Stopping, State.Unknown, State.Migrating);
		// Find routers for the network
		List<DomainRouterVO> routers = _routerDao.findByNetwork(nw.getId());
		List<VMInstanceVO>ins = new ArrayList<VMInstanceVO>();
		if (vms != null) {
			ins.addAll(vms);
		}
		if (routers.size() != 0) {
			ins.addAll(routers);
		}
		List<Long> toHostIds = new ArrayList<Long>();
		List<Long> fromHostIds = new ArrayList<Long>();
        for (VMInstanceVO v : ins) {
            Long rh = v.getHostId();
            if (rh == null || rh.longValue() == hostId) {
                continue;
            }
            OvsTunnelNetworkVO ta = _tunnelNetworkDao.getByFromToNetwork(hostId, rh.longValue(), nw.getId());
            // Try and create the tunnel even if a previous attempt failed
            if (ta == null || ta.getState().equals("FAILED")) {
            	s_logger.debug("Attempting to create tunnel from:" + hostId + " to:" + rh.longValue());
            	if (ta == null) {
            		this.createTunnelRecord(hostId, rh.longValue(), nw.getId(), key);
            	}
                if (!toHostIds.contains(rh)) {
                    toHostIds.add(rh);
                } 
            }

            ta = _tunnelNetworkDao.getByFromToNetwork(rh.longValue(), hostId, nw.getId());
            // Try and create the tunnel even if a previous attempt failed            
            if (ta == null || ta.getState().equals("FAILED")) {
            	s_logger.debug("Attempting to create tunnel from:" + rh.longValue() + " to:" + hostId);
            	if (ta == null) {
            		this.createTunnelRecord(rh.longValue(), hostId, nw.getId(), key);
            	} 
                if (!fromHostIds.contains(rh)) {
                    fromHostIds.add(rh);
                }
            }
        }
		//FIXME: Why are we cancelling the exception here?
		try {
			String myIp = dest.getHost().getPrivateIpAddress();
			boolean noHost = true;
			for (Long i : toHostIds) {
				HostVO rHost = _hostDao.findById(i);
				Commands cmds = new Commands(
						new OvsCreateTunnelCommand(rHost.getPrivateIpAddress(), key, 
											       Long.valueOf(hostId), i, nw.getId(), myIp));
				s_logger.debug("Ask host " + hostId + " to create gre tunnel to " + i);
				Answer[] answers = _agentMgr.send(hostId, cmds);
				handleCreateTunnelAnswer(answers);
				noHost = false;
			}
			
			for (Long i : fromHostIds) {
			    HostVO rHost = _hostDao.findById(i);
				Commands cmds = new Commands(
				        new OvsCreateTunnelCommand(myIp, key, i, Long.valueOf(hostId),
				        		                   nw.getId(), rHost.getPrivateIpAddress()));
				s_logger.debug("Ask host " + i + " to create gre tunnel to " + hostId);
				Answer[] answers = _agentMgr.send(i, cmds);
				handleCreateTunnelAnswer(answers);
				noHost = false;
			}
			// If not tunnels have been configured, perform the bridge setup anyway
			// This will ensure VIF rules will be triggered
			if (noHost) {
				Commands cmds = new Commands(
						new OvsSetupBridgeCommand(key, hostId, nw.getId()));
				s_logger.debug("Ask host " + hostId + " to configure bridge for network:" + nw.getId());
				Answer[] answers = _agentMgr.send(hostId, cmds);
				handleSetupBridgeAnswer(answers);
			}
		} catch (Exception e) {
		    s_logger.warn("Ovs Tunnel network created tunnel failed", e);
		}	
	}
	
	@Override
	public boolean start() {
		return true;
	}

	@Override
	public boolean stop() {
		return true;
	}

	@Override
	public String getName() {
		return _name;
	}

	@Override
	public boolean isOvsTunnelEnabled() {
		return _isEnabled;
	}

    @Override
    public void VmCheckAndCreateTunnel(VirtualMachineProfile<? extends VirtualMachine> vm, Network nw, DeployDestination dest) {
        CheckAndCreateTunnel(vm.getVirtualMachine(), nw, dest);    
    }

    @DB
    private void handleDestroyTunnelAnswer(Answer ans, long from, long to, long network_id) {
        
        if (ans.getResult()) {
            OvsTunnelNetworkVO lock = _tunnelNetworkDao.acquireInLockTable(Long.valueOf(1));
            if (lock == null) {
                s_logger.warn(String.format("failed to lock ovs_tunnel_account, remove record of " + 
                                            "tunnel(from=%1$s, to=%2$s account=%3$s) failed",
                                            from, to, network_id));
                return;
            }

            _tunnelNetworkDao.removeByFromToNetwork(from, to, network_id);
            _tunnelNetworkDao.releaseFromLockTable(lock.getId());
            
            s_logger.debug(String.format("Destroy tunnel(account:%1$s, from:%2$s, to:%3$s) successful",
            			   network_id, from, to)); 
        } else {
            s_logger.debug(String.format("Destroy tunnel(account:%1$s, from:%2$s, to:%3$s) failed",
            		       network_id, from, to));
        }
    }

    @DB
    private void handleDestroyBridgeAnswer(Answer ans, long host_id, long network_id) {
        
        if (ans.getResult()) {
            OvsTunnelNetworkVO lock = _tunnelNetworkDao.acquireInLockTable(Long.valueOf(1));
            if (lock == null) {
                s_logger.warn("failed to lock ovs_tunnel_network, remove record");
                return;
            }

            _tunnelNetworkDao.removeByFromNetwork(host_id, network_id);
            _tunnelNetworkDao.releaseFromLockTable(lock.getId());
            
            s_logger.debug(String.format("Destroy bridge for network %1$s successful", network_id)); 
        } else {
        	s_logger.debug(String.format("Destroy bridge for network %1$s failed", network_id));        
        }
    }

    private void handleSetupBridgeAnswer(Answer[] answers) {
    	//TODO: Add some error management here?
    	s_logger.debug("Placeholder for something more meanginful to come");
    }

    @Override
    public void CheckAndDestroyTunnel(VirtualMachine vm, Network nw) {
    	if (!_isEnabled) {
            return;
        }
        
        List<UserVmVO> userVms = _userVmDao.listByAccountIdAndHostId(vm.getAccountId(), vm.getHostId());
        if (vm.getType() == VirtualMachine.Type.User) {
            if (userVms.size() > 1) {
                return;
            }
            
            List<DomainRouterVO> routers = _routerDao.findByNetwork(nw.getId());
            for (DomainRouterVO router : routers) {
                if (router.getHostId() == vm.getHostId()) {
                	return;
                }
            }
        } else if (vm.getType() == VirtualMachine.Type.DomainRouter && userVms.size() != 0) {
                return;
        }
        try {
            /* Now we are last one on host, destroy the bridge with all 
             * the tunnels for this network  */
        	int key = getGreKey(nw);
            Command cmd = new OvsDestroyBridgeCommand(nw.getId(), key);
            s_logger.debug("Destroying bridge for network " + nw.getId() + " on host:" + vm.getHostId());
            Answer ans = _agentMgr.send(vm.getHostId(), cmd);
            handleDestroyBridgeAnswer(ans, vm.getHostId(), nw.getId());
            
            /* Then ask hosts have peer tunnel with me to destroy them */
            List<OvsTunnelNetworkVO> peers = _tunnelNetworkDao.listByToNetwork(vm.getHostId(), nw.getId());
            for (OvsTunnelNetworkVO p : peers) {
            	// If the tunnel was not successfully created don't bother to remove it
            	if (p.getState().equals("SUCCESS")) {
	                cmd = new OvsDestroyTunnelCommand(p.getNetworkId(), key, p.getPortName());
	                s_logger.debug("Destroying tunnel to " + vm.getHostId() + 
	                		" from " + p.getFrom());
	                ans = _agentMgr.send(p.getFrom(), cmd);
	                handleDestroyTunnelAnswer(ans, p.getFrom(), p.getTo(), p.getNetworkId());
            	}
            }
        } catch (Exception e) {
            s_logger.warn(String.format("Destroy tunnel(account:%1$s, hostId:%2$s) failed", vm.getAccountId(), vm.getHostId()), e);
        }
        
    }

}
